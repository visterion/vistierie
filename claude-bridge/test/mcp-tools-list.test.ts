import { describe, it, expect } from "vitest";
import { createSdkMcpServer, tool } from "@anthropic-ai/claude-agent-sdk";

import { deriveShape } from "../src/complete.js";

/**
 * The real advertising path, end to end and without mocks: the bridge's own
 * `deriveShape`, the Agent SDK's `tool()` / `createSdkMcpServer`, and an MCP
 * `tools/list` request answered by that server. `tools/list` is where the SDK
 * turns the derived Zod types into JSON Schema with its OWN bundled driver —
 * not with zod's `z.toJSONSchema` that `derive-shape.test.ts` calls. A skew
 * between the two (zod 4.5.4's record processor needs a context field the SDK
 * driver never creates) crashed `tools/list`, the CLI swallowed the error and
 * started the session with zero tools: the model wrote its tool calls as text
 * and the run ended green with nothing done. Only this path can see that.
 */
async function listTools(
  server: ReturnType<typeof createSdkMcpServer>,
): Promise<{ tools?: Array<{ name: string; inputSchema: unknown }>; error?: string }> {
  let deliver!: (m: unknown) => void;
  const reply = new Promise<any>((resolve) => {
    deliver = resolve;
  });
  const transport = {
    onmessage: undefined as ((m: unknown) => void) | undefined,
    onclose: undefined as (() => void) | undefined,
    onerror: undefined as ((e: Error) => void) | undefined,
    async start() {},
    async close() {
      transport.onclose?.();
    },
    async send(message: unknown) {
      if ((message as { id?: unknown }).id === 1) deliver(message);
    },
  };
  await server.instance.connect(transport as any);
  try {
    transport.onmessage!({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} });
    const msg = await reply;
    if (msg.error) return { error: String(msg.error.message) };
    return { tools: msg.result.tools };
  } finally {
    await server.instance.close();
  }
}

function serverFor(defs: Array<{ name: string; input_schema: unknown }>) {
  return createSdkMcpServer({
    name: "vistierie",
    version: "1.0.0",
    tools: defs.map((d) =>
      tool(d.name, d.name, deriveShape(d.input_schema), async () => ({ content: [] })),
    ),
  });
}

/** Synthetic: a result tool whose nested objects declare no `properties` at all. */
const resultWithBareObjects = {
  type: "object",
  required: ["items"],
  properties: {
    items: { type: "array", items: { type: "object" } },
    rejected: {
      type: "array",
      items: {
        type: "object",
        required: ["key", "reason"],
        properties: { key: { type: "string" }, reason: { type: "string" } },
      },
    },
    health: { type: "object" },
  },
};

/** Synthetic: typed array items, plus a single bare object property. */
const resultWithOneBareObject = {
  type: "object",
  required: ["items"],
  properties: {
    items: {
      type: "array",
      items: {
        type: "object",
        required: ["key", "score"],
        properties: {
          key: { type: "string" },
          score: { type: "number", minimum: 0, maximum: 1 },
          notes: { type: "array", items: { type: "string" } },
        },
      },
    },
    health: { type: "object" },
  },
};

const noArgs = { type: "object", properties: {} };

describe("tools/list through the Agent SDK's MCP server", () => {
  it.each([
    ["a bare object property", { type: "object", properties: { x: { type: "object" } } }],
    [
      "an array of bare objects",
      { type: "object", properties: { x: { type: "array", items: { type: "object" } } } },
    ],
    [
      "a bare object nested inside a typed object",
      {
        type: "object",
        properties: { outer: { type: "object", properties: { inner: { type: "object" } } } },
      },
    ],
    ["a nullable bare object", { type: "object", properties: { x: { type: ["object", "null"] } } }],
  ])("lists a tool whose schema has %s", async (_label, schema) => {
    const res = await listTools(serverFor([{ name: "t", input_schema: schema }]));

    expect(res.error).toBeUndefined();
    expect(res.tools?.map((t) => t.name)).toEqual(["t"]);
  });

  it.each([
    ["several bare objects", resultWithBareObjects, ["fetch_list", "submit_result"]],
    ["one bare object", resultWithOneBareObject, ["fetch_list", "check_item", "submit_result"]],
  ])("lists every tool of an agent whose result schema has %s", async (_label, schema, names) => {
    const defs = names.map((name) => ({
      name,
      input_schema: name === "submit_result" ? schema : noArgs,
    }));

    const res = await listTools(serverFor(defs));

    expect(res.error).toBeUndefined();
    expect(res.tools?.map((t) => t.name)).toEqual(names);
  });

  it("still advertises a bare object as an object that accepts any keys", async () => {
    const res = await listTools(
      serverFor([{ name: "t", input_schema: { type: "object", properties: { x: { type: "object" } } } }]),
    );

    const x = (res.tools?.[0].inputSchema as any).properties.x;
    expect(x.type).toBe("object");
    // Unknown keys stay allowed: an empty `additionalProperties` schema, never `false`.
    expect(x.additionalProperties).toEqual({});
  });
});
