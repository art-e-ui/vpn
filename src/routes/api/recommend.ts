import { createFileRoute } from "@tanstack/react-router";
import { generateServerRecommendation } from "@/lib/recommend.server";
import {
  containsConfigurationCredentials,
  recommendationRequestSchema,
} from "@/lib/recommend.schema";

const maxRequestBytes = 16_000;

export const Route = createFileRoute("/api/recommend")({
  server: {
    handlers: {
      POST: async ({ request }) => {
        const supabaseUrl = process.env["SUPABASE_URL"]?.replace(/\/+$/, "");
        const publishableKey = process.env["SUPABASE_PUBLISHABLE_KEY"];
        if (!supabaseUrl || !publishableKey) {
          console.error("[Android recommendation] Supabase server configuration is missing.");
          return Response.json(
            { error: "Authentication service is unavailable." },
            { status: 503 },
          );
        }

        const authorization = request.headers.get("authorization");
        const token = authorization?.startsWith("Bearer ") ? authorization.slice(7) : "";
        if (!token || token.split(".").length !== 3) {
          return Response.json(
            { error: "Sign in with Google before requesting a recommendation." },
            { status: 401 },
          );
        }

        let userResponse: Response;
        try {
          userResponse = await fetch(`${supabaseUrl}/auth/v1/user`, {
            headers: { apikey: publishableKey, Authorization: `Bearer ${token}` },
            signal: AbortSignal.timeout(10_000),
          });
        } catch (error) {
          console.error("[Android recommendation] Supabase token verification failed.", error);
          return Response.json(
            { error: "Authentication service is unavailable." },
            { status: 503 },
          );
        }
        if (userResponse.status === 401 || userResponse.status === 403) {
          return Response.json(
            { error: "Your Google session expired. Sign in again." },
            { status: 401 },
          );
        }
        if (!userResponse.ok) {
          console.error(
            `[Android recommendation] Supabase token verification returned HTTP ${userResponse.status}.`,
          );
          return Response.json(
            { error: "Authentication service is unavailable." },
            { status: 503 },
          );
        }

        const contentLength = Number(request.headers.get("content-length") || 0);
        if (contentLength > maxRequestBytes) {
          return Response.json({ error: "Recommendation request is too large." }, { status: 413 });
        }

        let body: unknown;
        try {
          const text = await request.text();
          if (new TextEncoder().encode(text).byteLength > maxRequestBytes) {
            return Response.json(
              { error: "Recommendation request is too large." },
              { status: 413 },
            );
          }
          body = JSON.parse(text);
        } catch {
          return Response.json(
            { error: "Recommendation request must be valid JSON." },
            { status: 400 },
          );
        }

        const parsed = recommendationRequestSchema.safeParse(body);
        if (!parsed.success || !parsed.data.apiKey?.trim()) {
          return Response.json(
            { error: "Provide a Gemini API key and supported server metadata." },
            { status: 400 },
          );
        }
        if (containsConfigurationCredentials(parsed.data.purpose)) {
          return Response.json(
            {
              error:
                "Configuration links and credentials must be imported locally, not sent for recommendations.",
            },
            { status: 400 },
          );
        }

        try {
          const result = await generateServerRecommendation(parsed.data, false);
          return Response.json(result, { status: "error" in result ? 502 : 200 });
        } catch (error) {
          console.error("[Android recommendation] Gemini request failed.", error);
          return Response.json({ error: "Gemini recommendation request failed." }, { status: 502 });
        }
      },
    },
  },
});
