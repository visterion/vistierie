import { describe, it, expect, vi, beforeEach } from "vitest";

const queryMock = vi.fn();
vi.mock("@anthropic-ai/claude-agent-sdk", () => ({ query: queryMock }));

const { complete, flattenMessages } = await import("../src/complete.js");

function sdkStream(messages: unknown[]) {
  return (async function* () {
    for (const m of messages) yield m;
  })();
}

beforeEach(() => queryMock.mockReset());

describe("flattenMessages", () => {
  it("turns string contents into text blocks, prefixing assistant turns", () => {
    const blocks = flattenMessages([
      { role: "user", content: "hi" },
      { role: "assistant", content: "hello!" },
      { role: "user", content: "and now?" },
    ]);
    expect(blocks).toEqual([
      { type: "text", text: "hi" },
      { type: "text", text: "[assistant]\nhello!" },
      { type: "text", text: "and now?" },
    ]);
  });

  it("passes image blocks through untouched", () => {
    const img = { type: "image", source: { type: "base64", media_type: "image/png", data: "AAAA" } };
    const blocks = flattenMessages([
      { role: "user", content: [img, { type: "text", text: "describe" }] },
    ]);
    expect(blocks).toEqual([img, { type: "text", text: "describe" }]);
  });
});

describe("complete", () => {
  it("maps a success result to the wire response", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "system", subtype: "init" },
      {
        type: "result",
        subtype: "success",
        result: "the answer",
        usage: {
          input_tokens: 12,
          output_tokens: 5,
          cache_creation_input_tokens: 1,
          cache_read_input_tokens: 2,
        },
      },
    ]));

    const res = await complete({
      model: "claude-opus-4-8",
      max_tokens: 256,
      system: "be brief",
      messages: [{ role: "user", content: "hi" }],
    });

    expect(res.text).toBe("the answer");
    expect(res.stop_reason).toBe("end_turn");
    expect(res.model).toBe("claude-opus-4-8");
    expect(res.usage).toEqual({
      input_tokens: 12,
      output_tokens: 5,
      cache_creation_input_tokens: 1,
      cache_read_input_tokens: 2,
    });

    const opts = queryMock.mock.calls[0][0].options;
    expect(opts.model).toBe("claude-opus-4-8");
    expect(opts.systemPrompt).toBe("be brief");
    expect(opts.maxTurns).toBe(1);
    expect(opts.allowedTools).toEqual([]);
  });

  it("throws BridgeError on error result", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "error_during_execution", errors: ["Claude AI usage limit reached"] },
    ]));
    await expect(complete({
      model: "claude-opus-4-8",
      messages: [{ role: "user", content: "hi" }],
    })).rejects.toMatchObject({ status: 429, code: "subscription_exhausted" });
  });

  it("maps auth error text from errors[] to auth_expired", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "error_during_execution", errors: ["OAuth token has expired"] },
    ]));
    await expect(complete({
      model: "claude-opus-4-8",
      messages: [{ role: "user", content: "hi" }],
    })).rejects.toMatchObject({ status: 500, code: "auth_expired" });
  });

  it("falls back to the subtype when an error result has no error text", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "error_max_turns", errors: [] },
    ]));
    await expect(complete({
      model: "claude-opus-4-8",
      messages: [{ role: "user", content: "hi" }],
    })).rejects.toMatchObject({ status: 500, code: "sdk_error", message: "error_max_turns" });
  });

  it("throws BridgeError when the stream ends without a result", async () => {
    queryMock.mockReturnValue(sdkStream([{ type: "system", subtype: "init" }]));
    await expect(complete({
      model: "claude-opus-4-8",
      messages: [{ role: "user", content: "hi" }],
    })).rejects.toMatchObject({ status: 500, code: "no_result" });
  });
});
