import { z } from "zod";

export const recommendationServerSchema = z
  .object({
    id: z.string().min(1).max(100),
    protocol: z.string().min(1).max(20),
    name: z.string().max(100).optional(),
    country: z.string().max(100).optional(),
    ping: z.number().finite().nonnegative().optional(),
  })
  .strict();

export const recommendationRequestSchema = z
  .object({
    purpose: z.string().trim().min(1).max(500),
    apiKey: z.string().max(250).optional(),
    servers: z.array(recommendationServerSchema).min(1).max(30),
  })
  .strict();

export type RecommendationRequest = z.infer<typeof recommendationRequestSchema>;

const configurationLinkPattern = /(?:vmess|vless|trojan|ss):\/\//i;
const configurationCredentialPattern = /["']?(?:uuid|password|privateKey|alterId)["']?\s*:/i;

export function containsConfigurationCredentials(purpose: string) {
  const trimmed = purpose.trim();
  return (
    trimmed.startsWith("{") ||
    trimmed.startsWith("[") ||
    configurationLinkPattern.test(trimmed) ||
    configurationCredentialPattern.test(trimmed)
  );
}
