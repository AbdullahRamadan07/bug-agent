import Anthropic from "@anthropic-ai/sdk";

type Block = { type: "text"; text: string } | { type: "tool_use"; name: string; input: unknown };

let seq = 0;

/** Encodes one assistant turn as the SSE stream the Messages API returns. */
export function sseTurn(model: string, blocks: Block[]): string {
  const ev = (type: string, data: unknown) => `event: ${type}\ndata: ${JSON.stringify({ type, ...(data as object) })}\n\n`;
  const id = `msg_${++seq}`;
  let out = ev("message_start", {
    message: { id, type: "message", role: "assistant", model, content: [], stop_reason: null, stop_sequence: null,
      usage: { input_tokens: 10, output_tokens: 1 } },
  });
  blocks.forEach((b, index) => {
    if (b.type === "text") {
      out += ev("content_block_start", { index, content_block: { type: "text", text: "" } });
      out += ev("content_block_delta", { index, delta: { type: "text_delta", text: b.text } });
    } else {
      out += ev("content_block_start", { index, content_block: { type: "tool_use", id: `toolu_${++seq}`, name: b.name, input: {} } });
      out += ev("content_block_delta", { index, delta: { type: "input_json_delta", partial_json: JSON.stringify(b.input) } });
    }
    out += ev("content_block_stop", { index });
  });
  const stop = blocks.some((b) => b.type === "tool_use") ? "tool_use" : "end_turn";
  out += ev("message_delta", { delta: { stop_reason: stop, stop_sequence: null }, usage: { output_tokens: 20 } });
  out += ev("message_stop", {});
  return out;
}

/**
 * An Anthropic client whose HTTP layer replays scripted turns in order
 * and records every request body, so agent code runs unmodified.
 */
export function scriptedClient(turns: string[]) {
  const requests: any[] = [];
  const fetchImpl = async (_url: unknown, init?: { body?: unknown }) => {
    requests.push(JSON.parse(String(init?.body)));
    const body = turns.shift();
    if (!body) throw new Error("scripted client ran out of turns");
    return new Response(body, { status: 200, headers: { "content-type": "text/event-stream", "request-id": "req_test" } });
  };
  const client = new Anthropic({ apiKey: "test-key", fetch: fetchImpl as unknown as typeof fetch, maxRetries: 0 });
  return { client, requests };
}
