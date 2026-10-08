import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { fakeSdkMcpServer } from "./fake-mcp-server.js";

const queryMock = vi.fn();
const createSdkMcpServerMock = vi.fn((opts: any) => fakeSdkMcpServer(opts));
const toolMock = vi.fn((name: string, description: string, inputSchema: any, handler: any) => ({
  name,
  description,
  inputSchema,
  handler,
}));
vi.mock("@anthropic-ai/claude-agent-sdk", () => ({
  query: queryMock,
  createSdkMcpServer: createSdkMcpServerMock,
  tool: toolMock,
}));

const { complete } = await import("../src/complete.js");
const { SessionStore } = await import("../src/sessions.js");

type U = {
  input_tokens: number;
  output_tokens: number;
  cache_creation_input_tokens: number;
  cache_read_input_tokens: number;
};

// Synthetic per-message usages; their sum is the spec §5 Opus 5.5 oracle tuple (4, 290, 3984, 28101).
const M1: U = { input_tokens: 2, output_tokens: 120, cache_creation_input_tokens: 3984, cache_read_input_tokens: 14000 };
const M2: U = { input_tokens: 2, output_tokens: 170, cache_creation_input_tokens: 0, cache_read_input_tokens: 14101 };
const TOTAL: U = { input_tokens: 4, output_tokens: 290, cache_creation_input_tokens: 3984, cache_read_input_tokens: 28101 };
const ZERO: U = { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
const BOGUS: U = { input_tokens: 999, output_tokens: 999, cache_creation_input_tokens: 999, cache_read_input_tokens: 999 };
const SERVED = "claude-opus-5-5";

function sdkStream(messages: unknown[]) {
  return (async function* () {
    for (const m of messages) yield m;
  })();
}

/** A hand-driven iterator: each step yields a message, optionally after a delay, or hangs forever. */
function scriptedIterator(steps: Array<{ msg?: unknown; delayMs?: number; hang?: boolean }>) {
  let i = 0;
  return {
    [Symbol.asyncIterator]() {
      return {
        next: async () => {
          const s = steps[i++];
          if (!s) return { done: true, value: undefined };
          if (s.hang) return new Promise<never>(() => {});
          if (s.delayMs) await new Promise((r) => setTimeout(r, s.delayMs));
          return { done: false, value: s.msg };
        },
      };
    },
  };
}

const ev = (event: Record<string, unknown>) => ({ type: "stream_event", parent_tool_use_id: null, event });
const messageStart = (id: string, model = SERVED) =>
  ev({
    type: "message_start",
    message: { id, model, usage: { input_tokens: 2, output_tokens: 1, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 } },
  });
const messageDelta = (usage: U) => ev({ type: "message_delta", delta: { stop_reason: null }, usage });
const messageStop = () => ev({ type: "message_stop" });
/** E3: assistant snapshots carry a streaming-snapshot usage that must never be counted. */
const SNAPSHOT_USAGE = { input_tokens: 2, output_tokens: 6, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
const toolSnapshot = (id: string, toolUseId: string, name: string, input: Record<string, unknown> = {}) => ({
  type: "assistant",
  message: {
    id,
    model: SERVED,
    content: [{ type: "tool_use", id: toolUseId, name: `mcp__vistierie__${name}`, input }],
    usage: SNAPSHOT_USAGE,
  },
});
const textSnapshot = (id: string, text: string) => ({
  type: "assistant",
  message: { id, model: SERVED, content: [{ type: "text", text }], usage: SNAPSHOT_USAGE },
});
const toolResultEcho = (toolUseId: string) => ({
  type: "user",
  message: { role: "user", content: [{ type: "tool_result", tool_use_id: toolUseId, content: "x" }] },
});
const result = (usage: unknown, extra: Record<string, unknown> = {}) => ({
  type: "result",
  subtype: "success",
  result: "done",
  is_error: false,
  usage,
  ...extra,
});

const TOOLS = [
  { name: "fetch_x", input_schema: { type: "object", properties: {} } },
  { name: "fetch_y", input_schema: { type: "object", properties: {} } },
  { name: "submit_result", input_schema: { type: "object", properties: {} } },
];
const STRUCTURED_TOOL = {
  name: "submit_items",
  input_schema: { type: "object", properties: { a: { type: "number" } } },
};
const ask = [{ role: "user", content: "go" }];

let warn: any;
beforeEach(() => {
  queryMock.mockReset();
  createSdkMcpServerMock.mockClear();
  toolMock.mockClear();
  warn = vi.spyOn(console, "warn").mockImplementation(() => {});
});
afterEach(() => warn.mockRestore());

function warnings(prefix: string): number {
  return warn.mock.calls.filter((c: unknown[]) => String(c[0]).startsWith(prefix)).length;
}

describe("stream events are requested on every query() path", () => {
  it("plain", async () => {
    queryMock.mockReturnValue(sdkStream([result(TOTAL)]));
    await complete({ model: "opus", messages: ask });
    expect(queryMock.mock.calls[0][0].options.includePartialMessages).toBe(true);
  });

  it("structured", async () => {
    queryMock.mockReturnValue(sdkStream([result(TOTAL, { structured_output: { a: 1 } })]));
    await complete({
      model: "opus",
      messages: ask,
      tools: [STRUCTURED_TOOL],
      tool_choice: { type: "tool", name: "submit_items" },
    });
    expect(queryMock.mock.calls[0][0].options.includePartialMessages).toBe(true);
  });

  it("tool session", async () => {
    queryMock.mockReturnValue(sdkStream([result(TOTAL)]));
    await complete({ model: "opus", tools: TOOLS, messages: ask }, { sessions: new SessionStore() });
    expect(queryMock.mock.calls[0][0].options.includePartialMessages).toBe(true);
  });
});

describe("per-turn usage from message_delta", () => {
  it("plain: sums the closed messages, never result.usage, and reports both models", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        messageStart("m1"), textSnapshot("m1", "thinking"), messageDelta(M1), messageStop(),
        messageStart("m2"), textSnapshot("m2", "done"), messageDelta(M2), messageStop(),
        result(BOGUS),
      ]),
    );
    const res = await complete({ model: "opus", messages: ask });
    expect(res.usage).toEqual(TOTAL);
    expect(res.model).toBe(SERVED);
    expect(res.requested_model).toBe("opus");
    expect(warnings("usage_events_missing")).toBe(0);
  });

  it("ignores the assistant snapshot's streaming usage (E3)", async () => {
    queryMock.mockReturnValue(
      sdkStream([messageStart("m1"), textSnapshot("m1", "hi"), messageDelta(M1), messageStop(), result(undefined)]),
    );
    const res = await complete({ model: "opus", messages: ask });
    expect(res.usage).toEqual(M1);
  });

  it("structured: sums two messages", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        messageStart("m1"), messageDelta(M1), messageStop(),
        messageStart("m2"), messageDelta(M2), messageStop(),
        result(BOGUS, { structured_output: { a: 1 } }),
      ]),
    );
    const res = await complete({
      model: "opus",
      messages: ask,
      tools: [STRUCTURED_TOOL],
      tool_choice: { type: "tool", name: "submit_items" },
    });
    expect(res.stop_reason).toBe("tool_use");
    expect(res.usage).toEqual(TOTAL);
    expect(res.model).toBe(SERVED);
    expect(res.requested_model).toBe("opus");
  });

  it("replay: sums two messages of the replayed session", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        messageStart("m1"), textSnapshot("m1", "a"), messageDelta(M1), messageStop(),
        messageStart("m2"), textSnapshot("m2", "b"), messageDelta(M2), messageStop(),
        result(BOGUS, { result: "replayed" }),
      ]),
    );
    const store = new SessionStore();
    const res = await complete(
      {
        model: "opus",
        session_id: "does-not-exist",
        tools: TOOLS,
        messages: [
          { role: "user", content: "q" },
          { role: "assistant", content: [{ type: "tool_use", id: "tu_1", name: "fetch_x", input: {} }] },
          { role: "user", content: [{ type: "tool_result", tool_use_id: "tu_1", content: { ok: true } }] },
        ],
      },
      { sessions: store },
    );
    expect(res.text).toBe("replayed");
    expect(res.usage).toEqual(TOTAL);
    expect(res.model).toBe(SERVED);
    expect(store.size()).toBe(0);
  });

  it("quota gate still reads result.usage: exhausted although deltas carry output", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        messageStart("m1"), messageDelta({ ...ZERO, input_tokens: 10, output_tokens: 50 }), messageStop(),
        result({ input_tokens: 10, output_tokens: 0 }, { result: "You've reached your usage limit. Try again later." }),
      ]),
    );
    await expect(complete({ model: "opus", messages: ask })).rejects.toMatchObject({
      status: 429,
      code: "subscription_exhausted",
    });
  });

  it("no stream events: tool turn 0, result turn result.usage, one usage_events_missing line", async () => {
    queryMock.mockReturnValue(sdkStream([toolSnapshot("m1", "tu_1", "fetch_x"), result(TOTAL, { result: "fin" })]));
    const store = new SessionStore();
    const first = await complete({ model: "opus", tools: TOOLS, messages: ask }, { sessions: store });
    expect(first.stop_reason).toBe("tool_use");
    expect(first.usage).toEqual(ZERO);
    expect(first.model).toBe("opus");
    expect(first.requested_model).toBe("opus");

    const last = await complete(
      {
        model: "opus",
        session_id: first.session_id,
        messages: [{ role: "user", content: [{ type: "tool_result", tool_use_id: "tu_1", content: "ok" }] }],
      },
      { sessions: store },
    );
    expect(last.text).toBe("fin");
    expect(last.usage).toEqual(TOTAL);
    expect(warnings("usage_events_missing")).toBe(1);
  });
});
