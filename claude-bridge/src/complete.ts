import { query } from "@anthropic-ai/claude-agent-sdk";
import type { SDKUserMessage } from "@anthropic-ai/claude-agent-sdk";
import { BridgeError, type CompleteRequest, type CompleteResponse } from "./types.js";
import { mapSdkError } from "./errors.js";

/**
 * Flatten the (opaque) Vistierie message history into one content-block list
 * for a single synthesized user turn. Assistant turns are prefixed so the
 * model can distinguish them; image blocks pass through untouched.
 */
export function flattenMessages(
  messages: CompleteRequest["messages"],
): Array<Record<string, unknown>> {
  const blocks: Array<Record<string, unknown>> = [];
  for (const m of messages) {
    const prefix = m.role === "assistant" ? "[assistant]\n" : "";
    if (typeof m.content === "string") {
      blocks.push({ type: "text", text: prefix + m.content });
    } else if (Array.isArray(m.content)) {
      for (const block of m.content as Array<Record<string, unknown>>) {
        if (block.type === "text") {
          blocks.push({ type: "text", text: prefix + String(block.text) });
        } else {
          blocks.push(block);
        }
      }
    }
  }
  return blocks;
}

export async function complete(req: CompleteRequest): Promise<CompleteResponse> {
  const content = flattenMessages(req.messages);

  async function* promptStream(): AsyncGenerator<SDKUserMessage> {
    yield {
      type: "user",
      message: { role: "user", content },
      parent_tool_use_id: null,
      session_id: "",
    } as unknown as SDKUserMessage;
  }

  try {
    const q = query({
      prompt: promptStream(),
      options: {
        model: req.model,
        systemPrompt: req.system ?? "",
        maxTurns: 1,
        allowedTools: [],
        settingSources: [],
      },
    });

    for await (const msg of q as AsyncIterable<Record<string, any>>) {
      if (msg.type !== "result") continue;
      if (msg.subtype === "success") {
        return {
          text: String(msg.result ?? ""),
          stop_reason: "end_turn",
          model: req.model,
          usage: {
            input_tokens: msg.usage?.input_tokens ?? 0,
            output_tokens: msg.usage?.output_tokens ?? 0,
            cache_creation_input_tokens: msg.usage?.cache_creation_input_tokens ?? 0,
            cache_read_input_tokens: msg.usage?.cache_read_input_tokens ?? 0,
          },
        };
      }
      throw mapSdkError(new Error(String(msg.result ?? msg.subtype)));
    }
    throw new BridgeError(500, "no_result", "SDK stream ended without a result message");
  } catch (err) {
    throw mapSdkError(err);
  }
}
