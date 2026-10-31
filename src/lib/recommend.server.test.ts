import { afterEach, describe, expect, it, vi } from "vitest";
import { generateServerRecommendation } from "./recommend.server";
import { recommendationRequestSchema, type RecommendationRequest } from "./recommend.schema";

const request: RecommendationRequest = {
  purpose: "Low-latency gaming",
  apiKey: "session-gemini-key",
  servers: [{ id: "public-node", protocol: "vless", country: "Japan" }],
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("same-origin Gemini recommendation service", () => {
  it("forwards only intent and public node metadata, keeping the key out of the model payload", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          candidates: [
            {
              content: {
                parts: [
                  {
                    text: '{"serverId":"public-node","dnsAddress":"1.1.1.1","routingMode":"Bypass LAN","reason":"VLESS is a suitable option."}',
                  },
                ],
              },
            },
          ],
        }),
        { status: 200 },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(generateServerRecommendation(request, false)).resolves.toEqual({
      serverId: "public-node",
      dnsAddress: "1.1.1.1",
      routingMode: "Bypass LAN",
      reason: "VLESS is a suitable option.",
    });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const modelPayload = JSON.parse(String(init.body)) as Record<string, unknown>;
    expect(modelPayload).not.toHaveProperty("apiKey");
    expect(JSON.stringify(modelPayload)).not.toContain("session-gemini-key");
    expect(init.headers).toMatchObject({ "x-goog-api-key": "session-gemini-key" });
  });

  it("rejects pasted configuration links before making a provider request", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      generateServerRecommendation(
        {
          ...request,
          purpose: "Use this vless://user-secret@example.invalid:443 link",
        },
        false,
      ),
    ).resolves.toHaveProperty("error");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejects configuration credentials in node fields", () => {
    expect(
      recommendationRequestSchema.safeParse({
        ...request,
        servers: [{ id: "node", protocol: "vless", configJson: '{"id":"credential"}' }],
      }).success,
    ).toBe(false);
  });

  it("rejects recommendations outside the supplied node list", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            candidates: [
              {
                content: {
                  parts: [
                    {
                      text: '{"serverId":"unknown-node","dnsAddress":"1.1.1.1","routingMode":"Bypass LAN","reason":"Not in list."}',
                    },
                  ],
                },
              },
            ],
          }),
          { status: 200 },
        ),
      ),
    );

    await expect(generateServerRecommendation(request, false)).resolves.toHaveProperty("error");
  });

  it("rejects DNS or routing suggestions outside the supported presets", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            candidates: [
              {
                content: {
                  parts: [
                    {
                      text: '{"serverId":"public-node","dnsAddress":"https://resolver.example","routingMode":"Global","reason":"Invalid DNS suggestion."}',
                    },
                  ],
                },
              },
            ],
          }),
          { status: 200 },
        ),
      ),
    );

    await expect(generateServerRecommendation(request, false)).resolves.toHaveProperty("error");
  });
});
