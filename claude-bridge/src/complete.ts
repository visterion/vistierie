import { query } from "@anthropic-ai/claude-agent-sdk";
import type { Options, SDKUserMessage } from "@anthropic-ai/claude-agent-sdk";
import { BridgeError, EFFORT_VALUES, type CompleteRequest, type CompleteResponse } from "./types.js";
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
        } else if (block.type === "tool_use") {
          blocks.push({
            type: "text",
            text: `${prefix}[tool_use ${String(block.id)}] ${String(block.name)} ${JSON.stringify(block.input ?? {})}`,
          });
        } else if (block.type === "tool_result") {
          blocks.push({
            type: "text",
            text: `${prefix}[tool_result ${String(block.tool_use_id)}] ${JSON.stringify(block.content ?? null)}`,
          });
        } else {
          blocks.push(block);
        }
      }
    } else if (m.content !== null && m.content !== undefined) {
      blocks.push({ type: "text", text: prefix + String(m.content) });
    }
  }
  return blocks;
}

/** Options controlling a single {@link complete} call. */
export interface CompleteOptions {
  /**
   * Abort signal tied to the incoming HTTP request. When it fires (client
   * disconnect / Java-side timeout), the in-flight SDK query is aborted so the
   * spawned CLI child process is torn down instead of leaking.
   */
  signal?: AbortSignal;
  /**
   * Hard upper bound (ms) on consuming the SDK query. On expiry the query is
   * aborted and a 504 timeout error is thrown. Defaults to
   * `BRIDGE_QUERY_TIMEOUT_MS` (or 290000, just under the Java 300s read timeout
   * so the bridge gives up first and cleans up).
   */
  timeoutMs?: number;
}

function resolveTimeoutMs(override?: number): number {
  if (typeof override === "number") return override;
  const fromEnv = Number(process.env.BRIDGE_QUERY_TIMEOUT_MS);
  return Number.isFinite(fromEnv) && fromEnv > 0 ? fromEnv : 290000;
}

export async function complete(
  req: CompleteRequest,
  opts: CompleteOptions = {},
): Promise<CompleteResponse> {
  if (req.effort !== undefined && !EFFORT_VALUES.includes(req.effort)) {
    throw new BridgeError(
      400,
      "invalid_request",
      `effort must be one of ${EFFORT_VALUES.join(", ")}`,
    );
  }

  const content = flattenMessages(req.messages);
  const timeoutMs = resolveTimeoutMs(opts.timeoutMs);

  async function* promptStream(): AsyncGenerator<SDKUserMessage> {
    yield {
      type: "user",
      message: { role: "user", content },
      parent_tool_use_id: null,
      session_id: "",
    } as unknown as SDKUserMessage;
  }

  // We own this controller and hand it to the SDK via `options.abortController`;
  // aborting it stops the query and tears down the spawned CLI child process.
  const controller = new AbortController();

  // A single rejection source for every non-completion outcome (timeout /
  // client disconnect). Whoever fires first also aborts the controller.
  let failWith!: (err: BridgeError) => void;
  const failure = new Promise<never>((_, reject) => {
    failWith = reject;
  });
  const abortWith = (err: BridgeError) => {
    if (!controller.signal.aborted) controller.abort();
    failWith(err);
  };

  const timer = setTimeout(
    () => abortWith(new BridgeError(504, "timeout", `SDK query exceeded ${timeoutMs}ms`)),
    timeoutMs,
  );

  const clientAbort = () =>
    abortWith(new BridgeError(499, "client_closed", "request aborted by client"));
  if (opts.signal) {
    if (opts.signal.aborted) clientAbort();
    else opts.signal.addEventListener("abort", clientAbort, { once: true });
  }

  const options: Options = {
    model: req.model,
    systemPrompt: req.system ?? "",
    maxTurns: 1,
    allowedTools: [],
    settingSources: [],
    abortController: controller,
  };
  if (req.effort === "off") {
    options.thinking = { type: "disabled" };
  } else if (req.effort !== undefined) {
    options.effort = req.effort;
  }
  // The Claude Agent SDK query Options type exposes no direct per-query
  // output-token cap, but the bundled CLI honors CLAUDE_CODE_MAX_OUTPUT_TOKENS,
  // so we forward req.max_tokens through the child process env.
  if (req.max_tokens !== undefined) {
    options.env = { ...process.env, CLAUDE_CODE_MAX_OUTPUT_TOKENS: String(req.max_tokens) };
  }

  async function consume(): Promise<CompleteResponse> {
    const q = query({
      prompt: promptStream(),
      options,
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
      // SDKResultError carries its error text in `errors: string[]` (no `result` field).
      const errorText =
        Array.isArray(msg.errors) && msg.errors.length > 0
          ? msg.errors.join("; ")
          : String(msg.subtype);
      throw mapSdkError(new Error(errorText));
    }
    throw new BridgeError(500, "no_result", "SDK stream ended without a result message");
  }

  try {
    return await Promise.race([consume(), failure]);
  } catch (err) {
    throw mapSdkError(err);
  } finally {
    clearTimeout(timer);
    opts.signal?.removeEventListener("abort", clientAbort);
  }
}
