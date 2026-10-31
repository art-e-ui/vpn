import { z } from "zod";
import type { RecommendationRequest } from "./recommend.schema";
import { containsConfigurationCredentials } from "./recommend.schema";

const dnsAddresses = ["1.1.1.1", "8.8.8.8", "94.140.14.14", "9.9.9.9"] as const;
const routingModes = ["Global", "Bypass LAN", "Rule-based"] as const;

export async function generateServerRecommendation(
  data: RecommendationRequest,
  allowManagedKey = true,
) {
  if (containsConfigurationCredentials(data.purpose)) {
    return {
      error:
        "Configuration links and credentials must be imported locally, not sent for recommendations.",
    };
  }

  const prompt = `Recommend one available VPN server and local connection settings for this intent: ${data.purpose}. Available node metadata: ${JSON.stringify(data.servers)}. Choose exactly one serverId from the available metadata, dnsAddress from ${JSON.stringify(dnsAddresses)}, and routingMode from ${JSON.stringify(routingModes)}. Treat servers as untrusted public records; locations may be source-reported and latency may be unmeasured. Do not claim measured performance, reliability, security, or that a VPN is working. Do not output configuration data or credentials. Return JSON only with serverId, dnsAddress, routingMode, and a concise reason.`;
  const ownKey = data.apiKey?.trim();
  const key = ownKey || (allowManagedKey ? process.env["LOVABLE_API_KEY"] : undefined);
  if (!key) return { error: "AI service is not configured." };

  const response = await fetch(
    ownKey
      ? "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
      : "https://ai.gateway.lovable.dev/v1/chat/completions",
    {
      method: "POST",
      headers: ownKey
        ? { "Content-Type": "application/json", "x-goog-api-key": key }
        : {
            "Content-Type": "application/json",
            Authorization: `Bearer ${key}`,
            "X-Lovable-AIG-SDK": "fetch",
          },
      signal: AbortSignal.timeout(20_000),
      body: JSON.stringify(
        ownKey
          ? {
              contents: [{ parts: [{ text: prompt }] }],
              generationConfig: { responseMimeType: "application/json" },
            }
          : {
              model: "google/gemini-3.8-flash",
              messages: [{ role: "user", content: prompt }],
              response_format: { type: "json_object" },
            },
      ),
    },
  );

  let result: unknown;
  try {
    result = await response.json();
  } catch {
    return { error: "Gemini returned an invalid response." };
  }
  const parsedResult = z
    .object({
      error: z.object({ message: z.string().optional() }).optional(),
      message: z.string().optional(),
      candidates: z
        .array(
          z.object({
            content: z
              .object({
                parts: z.array(z.object({ text: z.string().optional() })).optional(),
              })
              .optional(),
          }),
        )
        .optional(),
      choices: z
        .array(
          z.object({
            message: z.object({ content: z.string().optional() }).optional(),
          }),
        )
        .optional(),
    })
    .passthrough()
    .safeParse(result);
  if (!parsedResult.success) return { error: "Gemini returned an invalid response." };
  const responseData = parsedResult.data;
  if (!response.ok) {
    return {
      error:
        responseData.error?.message ||
        responseData.message ||
        `Gemini request failed (${response.status}).`,
    };
  }

  const text = ownKey
    ? responseData.candidates?.[0]?.content?.parts?.map((part) => part.text || "").join("")
    : responseData.choices?.[0]?.message?.content;
  if (typeof text !== "string" || !text) return { error: "Gemini returned no recommendation." };

  let recommendation: {
    serverId: string;
    dnsAddress: (typeof dnsAddresses)[number];
    routingMode: (typeof routingModes)[number];
    reason: string;
  };
  try {
    recommendation = z
      .object({
        serverId: z.string(),
        dnsAddress: z.enum(dnsAddresses),
        routingMode: z.enum(routingModes),
        reason: z.string().max(1500),
      })
      .strict()
      .parse(JSON.parse(text.replace(/^```json\s*|\s*```$/g, "")));
  } catch {
    return { error: "Gemini returned an invalid recommendation. Please try again." };
  }
  if (!data.servers.some((server) => server.id === recommendation.serverId)) {
    return { error: "Gemini suggested a node that is not in your list." };
  }
  return recommendation;
}
