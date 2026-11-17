import Anthropic from "@anthropic-ai/sdk";
import type { BetaRunnableTool } from "@anthropic-ai/sdk/lib/tools/BetaRunnableTool";

export type Effort = "low" | "medium" | "high" | "xhigh" | "max";

export interface AgentRunOptions {
  client: Anthropic;
  model: string;
  effort: Effort;
  system: string;
  prompt: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  tools: BetaRunnableTool<any>[];
  maxIterations: number;
  /** Server-side refusal fallback (Opus 5.5 / Sonnet 5.5 only — not available for Haiku). */
  fallback?: boolean;
  log?: (msg: string) => void;
}

export class AgentRefusalError extends Error {}

/**
 * Runs one agent to completion with the SDK tool runner (it owns the
 * request → tool → result loop). Returns the final assistant text.
 */
export async function runAgent(o: AgentRunOptions): Promise<{ text: string; usage: { input: number; output: number } }> {
  const runner = o.client.beta.messages.toolRunner({
    model: o.model,
    max_tokens: 32000,
    stream: true, // long agent turns: avoid HTTP timeouts
    system: [{ type: "text", text: o.system, cache_control: { type: "ephemeral" } }],
    output_config: { effort: o.effort },
    tools: o.tools,
    messages: [{ role: "user", content: o.prompt }],
    max_iterations: o.maxIterations,
    ...(o.fallback ? { betas: ["server-side-fallback-2026-07-01"], fallbacks: "default" as const } : {}),
  });

  const usage = { input: 0, output: 0 };
  let last: Anthropic.Beta.BetaMessage | undefined;
  for await (const stream of runner) {
    const msg = await stream.finalMessage();
    last = msg;
    usage.input += msg.usage.input_tokens + (msg.usage.cache_read_input_tokens ?? 0) + (msg.usage.cache_creation_input_tokens ?? 0);
    usage.output += msg.usage.output_tokens;
    for (const b of msg.content) {
      if (b.type === "tool_use") o.log?.(`  ${o.model} → ${b.name}(${preview(b.input)})`);
    }
    if (msg.stop_reason === "refusal") {
      throw new AgentRefusalError(`${o.model} declined the request (${msg.stop_details?.category ?? "unknown"})`);
    }
  }
  if (!last) throw new Error(`${o.model} returned no message`);
  const text = last.content.flatMap((b) => (b.type === "text" ? [b.text] : [])).join("\n").trim();
  return { text, usage };
}

function preview(input: unknown): string {
  const s = JSON.stringify(input) ?? "";
  return s.length > 140 ? s.slice(0, 140) + "…" : s;
}
