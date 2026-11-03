import { createServerFn } from '@tanstack/react-start';
import { z } from 'zod';

export const recommendServer = createServerFn({ method: 'POST' })
  .validator((data: unknown) => z.object({ purpose: z.string().trim().min(1).max(500), apiKey: z.string().max(250).optional(), servers: z.array(z.object({ id: z.string().max(100), name: z.string().max(100), protocol: z.string().max(20), ping: z.number() })).min(1).max(30) }).parse(data))
  .handler(async ({ data }) => {
    const prompt = `Recommend one VPN configuration for this intent: ${data.purpose}. Available nodes: ${JSON.stringify(data.servers)}. These are simulated test nodes. Do not claim measured performance or a working VPN. Return JSON only with serverId (an exact available id) and reason (a concise 1-2 sentence protocol and latency explanation). Never output credentials.`;
    const ownKey = data.apiKey?.trim();
    const key = ownKey || process.env['LOVABLE_API_KEY'];
    if (!key) return { error: 'AI service is not configured.' };
    const response = await fetch(ownKey ? 'https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent' : 'https://ai.gateway.lovable.dev/v1/chat/completions', {
      method: 'POST', headers: ownKey ? { 'Content-Type': 'application/json', 'x-goog-api-key': key } : { 'Content-Type': 'application/json', Authorization: `Bearer ${key}`, 'X-Lovable-AIG-SDK': 'fetch' },
      body: JSON.stringify(ownKey ? { contents: [{ parts: [{ text: prompt }] }], generationConfig: { responseMimeType: 'application/json' } } : { model: 'google/gemini-3.8-flash', messages: [{ role: 'user', content: prompt }], response_format: { type: 'json_object' } }),
    });
    const result = await response.json();
    if (!response.ok) return { error: result.error?.message || result.message || `Gemini request failed (${response.status}).` };
    const text = ownKey ? result.candidates?.[0]?.content?.parts?.map((p: { text?: string }) => p.text || '').join('') : result.choices?.[0]?.message?.content;
    if (!text) return { error: 'Gemini returned no recommendation.' };
    try {
      const recommendation = z.object({ serverId: z.string(), reason: z.string().max(1500) }).parse(JSON.parse(text.replace(/^```json\s*|\s*```$/g, '')));
      if (!data.servers.some(s => s.id === recommendation.serverId)) return { error: 'Gemini suggested a node that is not in your list.' };
      return recommendation;
    } catch { return { error: 'Gemini returned an invalid recommendation. Please try again.' }; }
  });