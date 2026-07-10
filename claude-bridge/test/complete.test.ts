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

  it("coerces non-string/non-array content to a text block via String()", () => {
    const blocks = flattenMessages([{ role: "user", content: 42 }]);
    expect(blocks).toEqual([{ type: "text", text: "42" }]);
  });

  it("emits no block for null/undefined content", () => {
    const blocks = flattenMessages([
      { role: "user", content: null },
      { role: "user", content: undefined },
    ]);
    expect(blocks).toEqual([]);
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

function successStream() {
  return sdkStream([
    {
      type: "result",
      subtype: "success",
      result: "ok",
      usage: { input_tokens: 1, output_tokens: 1, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 },
    },
  ]);
}

describe("effort mapping", () => {
  it('maps "off" to thinking disabled', async () => {
    queryMock.mockReturnValue(successStream());
    await complete({ model: "m", messages: [{ role: "user", content: "hi" }], effort: "off" });
    const opts = queryMock.mock.calls[0][0].options;
    expect(opts.thinking).toEqual({ type: "disabled" });
    expect(opts.effort).toBeUndefined();
  });

  it.each(["low", "medium", "high", "max"] as const)(
    'maps "%s" to the SDK effort option',
    async (level) => {
      queryMock.mockReturnValue(successStream());
      await complete({ model: "m", messages: [{ role: "user", content: "hi" }], effort: level });
      const opts = queryMock.mock.calls[0][0].options;
      expect(opts.effort).toBe(level);
      expect(opts.thinking).toBeUndefined();
    },
  );

  it("sets neither thinking nor effort when the field is absent", async () => {
    queryMock.mockReturnValue(successStream());
    await complete({ model: "m", messages: [{ role: "user", content: "hi" }] });
    const opts = queryMock.mock.calls[0][0].options;
    expect(opts.thinking).toBeUndefined();
    expect(opts.effort).toBeUndefined();
  });

  it("rejects unknown effort values with 400 before calling the SDK", async () => {
    await expect(
      complete({ model: "m", messages: [{ role: "user", content: "hi" }], effort: "turbo" as never }),
    ).rejects.toMatchObject({ status: 400, code: "invalid_request" });
    expect(queryMock).not.toHaveBeenCalled();
  });
});

describe("max_tokens passthrough", () => {
  it("forwards max_tokens as CLAUDE_CODE_MAX_OUTPUT_TOKENS in env", async () => {
    queryMock.mockReturnValue(successStream());
    await complete({ model: "m", max_tokens: 256, messages: [{ role: "user", content: "hi" }] });
    const opts = queryMock.mock.calls[0][0].options;
    expect(opts.env.CLAUDE_CODE_MAX_OUTPUT_TOKENS).toBe("256");
  });

  it("passes no env override when max_tokens is absent", async () => {
    queryMock.mockReturnValue(successStream());
    await complete({ model: "m", messages: [{ role: "user", content: "hi" }] });
    const opts = queryMock.mock.calls[0][0].options;
    expect(opts.env).toBeUndefined();
  });
});
