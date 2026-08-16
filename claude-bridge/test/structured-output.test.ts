import { describe, it, expect, vi, beforeEach } from "vitest";

const queryMock = vi.fn();
// The tool path also reaches for the SDK's in-process MCP server factories, so all three
// exports must be mocked — same harness as test/complete-tools.test.ts. The `any` parameters
// mirror that file's existing style.
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

function successResult(extra: Record<string, unknown> = {}) {
  return {
    type: "result",
    subtype: "success",
    result: "",
    is_error: false,
    usage: {
      input_tokens: 11,
      output_tokens: 22,
      cache_creation_input_tokens: 3,
      cache_read_input_tokens: 4,
    },
    ...extra,
  };
}

const SCHEMA = {
  type: "object",
  required: ["items"],
  properties: { items: { type: "array", items: { type: "string" } } },
};
const TOOL = { name: "submit_items", description: "synthetic tool", input_schema: SCHEMA };
const FORCED = { type: "tool", name: "submit_items" };
const base = { model: "test-model", messages: [{ role: "user", content: "hi" }] };

beforeEach(() => {
  queryMock.mockReset();
  createSdkMcpServerMock.mockClear();
  toolMock.mockReset();
});

describe("structured-output route", () => {
  it("asks the SDK for structured output and starts no MCP server", async () => {
    queryMock.mockReturnValue(sdkStream([successResult({ structured_output: { items: ["x"] } })]));

    await complete({ ...base, tools: [TOOL], tool_choice: FORCED });

    expect(queryMock).toHaveBeenCalledTimes(1);
    const options = queryMock.mock.calls[0][0].options;
    expect(options.outputFormat).toEqual({ type: "json_schema", schema: SCHEMA });
    expect(options.mcpServers).toBeUndefined();
    expect(options.allowedTools).toEqual([]);
  });

  it("returns the structured payload as a single tool_use block", async () => {
    const structured = { items: ["a", "b"] };
    queryMock.mockReturnValue(sdkStream([successResult({ structured_output: structured })]));

    const res = await complete({ ...base, tools: [TOOL], tool_choice: FORCED });

    expect(res.stop_reason).toBe("tool_use");
    expect(res.text).toBe("");
    expect(res.content_blocks).toHaveLength(1);
    const block = res.content_blocks![0];
    expect(block.type).toBe("tool_use");
    expect(block.name).toBe("submit_items");
    expect(block.input).toEqual(structured);
    expect(typeof block.id).toBe("string");
    expect(String(block.id).length).toBeGreaterThan(0);
    expect(res.usage).toEqual({
      input_tokens: 11,
      output_tokens: 22,
      cache_creation_input_tokens: 3,
      cache_read_input_tokens: 4,
    });
  });

  it("fails with 502 when subtype is success but structured_output is absent", async () => {
    queryMock.mockReturnValue(sdkStream([successResult()]));

    await expect(
      complete({ ...base, tools: [TOOL], tool_choice: FORCED }),
    ).rejects.toMatchObject({ status: 502, code: "structured_output_missing" });
  });

  it("still reports an upstream API error instead of a structured response", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        successResult({
          is_error: true,
          result: "API Error: 529 overloaded",
          structured_output: { items: [] },
        }),
      ]),
    );

    await expect(
      complete({ ...base, tools: [TOOL], tool_choice: FORCED }),
    ).rejects.toMatchObject({ status: 529, code: "upstream_api_error" });
  });

  it("still reports quota exhaustion instead of a structured response", async () => {
    queryMock.mockReturnValue(
      sdkStream([
        successResult({
          result: "You've hit your session limit",
          usage: { input_tokens: 5, output_tokens: 0 },
        }),
      ]),
    );

    await expect(
      complete({ ...base, tools: [TOOL], tool_choice: FORCED }),
    ).rejects.toMatchObject({ status: 429, code: "subscription_exhausted" });
  });

  it("leaves plain completions untouched", async () => {
    queryMock.mockReturnValue(sdkStream([successResult({ result: "hello" })]));

    const res = await complete({ ...base });

    expect(queryMock.mock.calls[0][0].options.outputFormat).toBeUndefined();
    expect(res.text).toBe("hello");
    expect(res.stop_reason).toBe("end_turn");
    expect(res.content_blocks).toBeUndefined();
  });
});

describe("requests that must keep the agentic tool path", () => {
  // The tool path needs a real session store — `completeTool` throws "tool mode requires a
  // session store" before ever reaching the SDK otherwise.
  async function expectToolPath(req: Record<string, unknown>) {
    queryMock.mockReturnValue(sdkStream([]));
    await complete(req as never, { sessions: new SessionStore() }).catch(() => undefined);
    expect(queryMock).toHaveBeenCalled();
    const options = queryMock.mock.calls[0][0].options;
    expect(options.mcpServers).toBeDefined();
    expect(options.outputFormat).toBeUndefined();
  }

  it("tools without tool_choice", async () => {
    await expectToolPath({ ...base, tools: [TOOL] });
  });

  it("tool_choice naming an unknown tool", async () => {
    await expectToolPath({ ...base, tools: [TOOL], tool_choice: { type: "tool", name: "other" } });
  });

  it("tool_choice of type any", async () => {
    await expectToolPath({ ...base, tools: [TOOL], tool_choice: { type: "any" } });
  });

  it("a named tool that carries no input_schema", async () => {
    const bare = { name: "submit_items", description: "synthetic tool" };
    await expectToolPath({ ...base, tools: [bare], tool_choice: FORCED });
  });

  it("a request continuing a session", async () => {
    queryMock.mockReturnValue(sdkStream([]));
    await complete(
      { ...base, tools: [TOOL], tool_choice: FORCED, session_id: "sess-1" } as never,
      { sessions: new SessionStore() },
    ).catch(() => undefined);
    expect(queryMock).toHaveBeenCalled();
    expect(queryMock.mock.calls[0][0].options.outputFormat).toBeUndefined();
  });
});
