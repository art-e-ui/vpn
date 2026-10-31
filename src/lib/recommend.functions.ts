import { createServerFn } from "@tanstack/react-start";
import { z } from "zod";
import { recommendationServerSchema } from "./recommend.schema";

export const recommendServer = createServerFn({ method: "POST" })
  .validator((data: unknown) =>
    z
      .object({
        purpose: z.string().trim().min(1).max(500),
        apiKey: z.string().max(250).optional(),
        servers: z
          .array(
            recommendationServerSchema.extend({
              name: z.string().max(100),
              ping: z.number().finite().nonnegative(),
            }),
          )
          .min(1)
          .max(30),
      })
      .strict()
      .parse(data),
  )
  .handler(async ({ data }) => {
    const { generateServerRecommendation } = await import("./recommend.server");
    return generateServerRecommendation(data);
  });
