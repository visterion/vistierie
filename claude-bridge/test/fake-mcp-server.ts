/**
 * Stand-in for the Agent SDK's `createSdkMcpServer` in tests that mock the SDK. Its
 * `instance` answers the one MCP request the bridge itself sends — `tools/list`, from the
 * session-start guard — with the names of the tools it was built from, so mocked tool-mode
 * tests pass the guard exactly like a healthy real server does.
 */
export function fakeSdkMcpServer(opts: { name: string; tools?: Array<{ name: string }> }) {
  const names = (opts.tools ?? []).map((t) => t.name);
  let transport: any;
  return {
    type: "sdk" as const,
    name: opts.name,
    instance: {
      async connect(t: any) {
        transport = t;
        t.onmessage = (msg: any) => {
          if (msg?.method === "tools/list") {
            void t.send({
              jsonrpc: "2.0",
              id: msg.id,
              result: { tools: names.map((name) => ({ name, inputSchema: { type: "object" } })) },
            });
          }
        };
        await t.start();
      },
      async close() {
        await transport?.close();
        transport = undefined;
      },
    },
  };
}
