import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { z } from "zod";

/**
 * The session-start guard against a tool-less session, run against the REAL in-process
 * MCP server of the Agent SDK. Only `query` (which would spawn the CLI) is mocked; `tool()`
 * is wrapped so a test can plant a broken shape or a renamed tool exactly where production
 * would break.
 */
const queryMock = vi.fn();
const toolOverrides = new Map<string, (actual: any) => any>();

vi.mock("@anthropic-ai/claude-agent-sdk", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@anthropic-ai/claude-agent-sdk")>();
  return {
    ...actual,
    query: queryMock,
    tool: (name: string, description: string, shape: any, handler: any) => {
      const override = toolOverrides.get(name);
      return override
        ? override(actual)
        : actual.tool(name, description, shape, handler);
    },
  };
});

const { complete, listMcpToolNames } = await import("../src/complete.js");
const { SessionStore } = await import("../src/sessions.js");

const noArgs = { type: "object", properties: {} };

function toolRequest(names: string[]) {
  return {
    model: "claude-opus-4-8",
    tools: names.map((name) => ({ name, input_schema: noArgs })),
    messages: [{ role: "user", content: "go" }],
  };
}

function never() {
  return (async function* () {
    await new Promise(() => {});
  })();
}

let errorLog: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  queryMock.mockReset();
  queryMock.mockImplementation(() => never());
  toolOverrides.clear();
  errorLog = vi.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
  errorLog.mockRestore();
});

describe("tool-mode session start guard", () => {
  it("fails with 502 tools_unavailable when the MCP server cannot list its tools", async () => {
    // Originally reproduced with a Zod record field (`z.record(z.string(), z.any())`), which
    // the SDK's bundled JSON-schema driver up to 0.3.260 could not serialise: tools/list threw
    // "Cannot read properties of undefined (reading 'push')" for the whole server. Verified
    // against claude-agent-sdk 0.3.292 (2026-10-07): that record shape now serialises fine and
    // the tool is listed. The SDK instead drops a tool with a genuinely unconvertible shape
    // (e.g. a field typed `z.bigint()`) from tools/list with a console warning
    // (`CLAUDE_SDK_MCP_TOOL_SCHEMA_UNCONVERTIBLE`) rather than throwing — so the danger this
    // guard exists for (a tool silently missing from the list) still occurs, just via the
    // "missing" branch of `assertToolsListed` instead of the "listing failed" branch. This
    // tool shape reproduces that current failure mode.
    toolOverrides.set("submit_result", (actual) =>
      actual.tool("submit_result", "d", { x: z.bigint().optional() }, async () => ({
        content: [],
      })),
    );
    const store = new SessionStore();

    const err = await complete(toolRequest(["fetch_list", "submit_result"]), { sessions: store }).catch(
      (e) => e,
    );

    expect(err).toMatchObject({ status: 502, code: "tools_unavailable" });
    expect(err.message).toContain("mcp__vistierie__submit_result");
    expect(err.message).toContain("listed:");
    // No CLI child is spawned and no session is parked for a run that could not use tools.
    expect(queryMock).not.toHaveBeenCalled();
    expect(store.size()).toBe(0);
    // ... and it is loud.
    expect(errorLog).toHaveBeenCalledWith(expect.stringContaining("tools_unavailable"));
  });

  it("fails with 502 tools_unavailable when a requested tool is not advertised", async () => {
    toolOverrides.set("check_item", (actual) =>
      actual.tool("something_else", "d", {}, async () => ({ content: [] })),
    );
    const store = new SessionStore();

    const err = await complete(toolRequest(["fetch_list", "check_item"]), { sessions: store }).catch(
      (e) => e,
    );

    expect(err).toMatchObject({ status: 502, code: "tools_unavailable" });
    expect(err.message).toContain("mcp__vistierie__check_item");
    expect(err.message).not.toContain("mcp__vistierie__fetch_list,");
    expect(queryMock).not.toHaveBeenCalled();
    expect(store.size()).toBe(0);
    expect(errorLog).toHaveBeenCalledWith(expect.stringContaining("mcp__vistierie__check_item"));
  });

  it("starts the session when every tool is listed, leaving the server free for the SDK", async () => {
    const store = new SessionStore();

    // The query never yields, so the call stays pending once the session has started.
    void complete(toolRequest(["fetch_list", "submit_result"]), { sessions: store, timeoutMs: 60_000 }).catch(
      () => {},
    );
    await vi.waitFor(() => expect(queryMock).toHaveBeenCalledTimes(1));

    // The SDK connects its own transport to this exact server when the session starts; the
    // guard's listing must have released it again, or that connect would throw.
    const server = queryMock.mock.calls[0][0].options.mcpServers.vistierie;
    expect(await listMcpToolNames(server)).toEqual(["fetch_list", "submit_result"]);
    expect(errorLog).not.toHaveBeenCalled();

    for (const id of [...(store as any).sessions.keys()]) store.close(id);
  });
});
