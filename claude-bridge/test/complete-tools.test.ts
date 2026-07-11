import { describe, it, expect, vi, beforeEach } from "vitest";

const queryMock = vi.fn();
// The in-process MCP server + tool factories from the Agent SDK. The mock
// captures the registered tools so a test can invoke a tool's handler directly
// (the real SDK would invoke it when the model emits a tool_use).
const createSdkMcpServerMock = vi.fn((opts: any) => ({ type: "sdk", name: opts.name, instance: {} }));
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

function sdkStream(messages: unknown[]) {
  return (async function* () {
    for (const m of messages) yield m;
  })();
}

const usage = {
  input_tokens: 3,
  output_tokens: 2,
  cache_creation_input_tokens: 0,
  cache_read_input_tokens: 0,
};

beforeEach(() => {
  queryMock.mockReset();
  createSdkMcpServerMock.mockClear();
  toolMock.mockReset();
  toolMock.mockImplementation((name: string, description: string, inputSchema: any, handler: any) => ({
    name,
    description,
    inputSchema,
    handler,
  }));
});

describe("tool-start", () => {
  it("parks the query on tool_use and returns the block + session_id", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        {
          type: "assistant",
          message: { content: [{ type: "tool_use", id: "tu_1", name: "fetch_x", input: { a: 1 } }] },
        },
        { type: "result", subtype: "success", result: "done", usage },
      ]),
    );

    const store = new SessionStore();
    const res = await complete(
      {
        model: "claude-opus-4-8",
        tools: [{ name: "fetch_x", input_schema: { type: "object", properties: { a: { type: "number" } } } }],
        messages: [{ role: "user", content: "go" }],
      },
      { sessions: store },
    );

    expect(res.stop_reason).toBe("tool_use");
    expect(res.text).toBe("");
    expect(res.content_blocks?.[0]).toMatchObject({ type: "tool_use", id: "tu_1", name: "fetch_x" });
    expect(res.session_id).toBeTruthy();
    expect(res.usage).toEqual({
      input_tokens: 0,
      output_tokens: 0,
      cache_creation_input_tokens: 0,
      cache_read_input_tokens: 0,
    });
    expect(store.size()).toBe(1);

    // Tool registered as an in-process MCP tool under server name "vistierie".
    expect(createSdkMcpServerMock).toHaveBeenCalledTimes(1);
    expect(createSdkMcpServerMock.mock.calls[0][0].name).toBe("vistierie");
    const opts = queryMock.mock.calls[0][0].options;
    expect(opts.allowedTools).toEqual(["mcp__vistierie__fetch_x"]);
    expect(opts.mcpServers).toHaveProperty("vistierie");
  });
});

describe("tool-continue", () => {
  it("resolves the parked handler and pumps to the final result", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        {
          type: "assistant",
          message: { content: [{ type: "tool_use", id: "tu_1", name: "fetch_x", input: { a: 1 } }] },
        },
        { type: "result", subtype: "success", result: "the answer", usage },
      ]),
    );

    const store = new SessionStore();
    const start = await complete(
      {
        model: "claude-opus-4-8",
        tools: [{ name: "fetch_x", input_schema: { type: "object", properties: { a: { type: "number" } } } }],
        messages: [{ role: "user", content: "go" }],
      },
      { sessions: store },
    );

    // The SDK would invoke this; drive it manually to assert it resolves.
    const registered = toolMock.mock.results[0].value as { handler: (a: unknown, e: unknown) => Promise<unknown> };
    const handlerPromise = registered.handler({ a: 1 }, {});

    const cont = await complete(
      {
        model: "claude-opus-4-8",
        session_id: start.session_id,
        messages: [
          { role: "user", content: [{ type: "tool_result", tool_use_id: "tu_1", content: { ok: true } }] },
        ],
      },
      { sessions: store },
    );

    expect(cont.stop_reason).toBe("end_turn");
    expect(cont.text).toBe("the answer");
    expect(cont.usage).toEqual(usage);
    expect(store.size()).toBe(0);

    await expect(handlerPromise).resolves.toEqual({
      content: [{ type: "text", text: '{"ok":true}' }],
      isError: false,
    });

    // query() ran exactly once — continue reuses the live session's iterator.
    expect(queryMock).toHaveBeenCalledTimes(1);
  });

  it("rejects a continue whose tool_use_id is unknown with 400", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        {
          type: "assistant",
          message: { content: [{ type: "tool_use", id: "tu_1", name: "fetch_x", input: {} }] },
        },
        { type: "result", subtype: "success", result: "x", usage },
      ]),
    );

    const store = new SessionStore();
    const start = await complete(
      {
        model: "m",
        tools: [{ name: "fetch_x", input_schema: { type: "object", properties: {} } }],
        messages: [{ role: "user", content: "go" }],
      },
      { sessions: store },
    );

    await expect(
      complete(
        {
          model: "m",
          session_id: start.session_id,
          messages: [{ role: "user", content: [{ type: "tool_result", tool_use_id: "nope", content: {} }] }],
        },
        { sessions: store },
      ),
    ).rejects.toMatchObject({ status: 400, code: "invalid_request" });
    // Session survives a bad continue so the caller can retry.
    expect(store.size()).toBe(1);
  });
});

describe("tool-replay", () => {
  it("runs a fresh query seeded from flattened history when the session is gone", async () => {
    queryMock.mockImplementation(() => sdkStream([{ type: "result", subtype: "success", result: "replayed", usage }]));

    const store = new SessionStore();
    const res = await complete(
      {
        model: "m",
        session_id: "does-not-exist",
        tools: [{ name: "fetch_x", input_schema: { type: "object", properties: {} } }],
        messages: [
          { role: "user", content: "q" },
          { role: "assistant", content: [{ type: "tool_use", id: "tu_1", name: "fetch_x", input: {} }] },
          { role: "user", content: [{ type: "tool_result", tool_use_id: "tu_1", content: { ok: true } }] },
        ],
      },
      { sessions: store },
    );

    expect(res.stop_reason).toBe("end_turn");
    expect(res.text).toBe("replayed");
    expect(store.size()).toBe(0);
    expect(queryMock).toHaveBeenCalledTimes(1);

    // The replayed prompt carries the flattened tool_result text.
    const promptGen = queryMock.mock.calls[0][0].prompt as AsyncGenerator<any>;
    const first = await promptGen.next();
    const text = JSON.stringify(first.value.message.content);
    expect(text).toContain("[tool_result tu_1]");
  });
});

describe("plain mode regression", () => {
  it("does not create a session and never builds an MCP server", async () => {
    queryMock.mockReturnValue(sdkStream([{ type: "result", subtype: "success", result: "ok", usage }]));
    const store = new SessionStore();
    const res = await complete(
      { model: "m", messages: [{ role: "user", content: "hi" }] },
      { sessions: store },
    );
    expect(res.stop_reason).toBe("end_turn");
    expect(res.text).toBe("ok");
    expect(res.session_id).toBeUndefined();
    expect(store.size()).toBe(0);
    expect(createSdkMcpServerMock).not.toHaveBeenCalled();
    const opts = queryMock.mock.calls[0][0].options;
    expect(opts.maxTurns).toBe(1);
    expect(opts.mcpServers).toBeUndefined();
  });
});

describe("session cap", () => {
  it("returns 503 session_limit when the store is at capacity", async () => {
    queryMock.mockReturnValue(sdkStream([{ type: "result", subtype: "success", result: "x", usage }]));
    const store = new SessionStore({ cap: 0 });
    await expect(
      complete(
        {
          model: "m",
          tools: [{ name: "fetch_x", input_schema: { type: "object", properties: {} } }],
          messages: [{ role: "user", content: "go" }],
        },
        { sessions: store },
      ),
    ).rejects.toMatchObject({ status: 503, code: "session_limit" });
  });
});
