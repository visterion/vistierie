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

  it("renders tool_use blocks as text", () => {
    const blocks = flattenMessages([
      { role: "assistant", content: [{ type: "tool_use", id: "tu_1", name: "fetch_x", input: { a: 1 } }] },
    ]);
    expect(blocks).toEqual([{ type: "text", text: '[assistant]\n[tool_use tu_1] fetch_x {"a":1}' }]);
  });

  it("renders tool_result blocks as text", () => {
    const blocks = flattenMessages([
      { role: "user", content: [{ type: "tool_result", tool_use_id: "tu_1", content: { ok: true } }] },
    ]);
    expect(blocks).toEqual([{ type: "text", text: '[tool_result tu_1] {"ok":true}' }]);
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
    // maxTurns must be > 1: reasoning/high-effort plain completions spend the first
    // turn thinking and abort at maxTurns:1 before emitting a result ("Reached maximum
    // number of turns (1)"), forcing a metered fallback. allowedTools:[] forbids any
    // tool loop, so a small bound is safe. See docs/bugs/2026-07-19-claude-bridge-maxturns-plain-path.md
    expect(opts.maxTurns).toBe(8);
    expect(opts.allowedTools).toEqual([]);
  });

  it("maps a usage-limit success result (0 output tokens) to a 429", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "success", result: "You've reached your usage limit. Try again later.",
        usage: { input_tokens: 10, output_tokens: 0 } },
    ]));
    await expect(complete({ model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] }))
      .rejects.toMatchObject({ status: 429, code: "subscription_exhausted" });
  });

  it("maps the captured weekly-limit success (0 output tokens) to a 429", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "success", result: "You've hit your weekly limit · resets 9am (UTC)",
        usage: { input_tokens: 10, output_tokens: 0 } },
    ]));
    await expect(complete({ model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] }))
      .rejects.toMatchObject({ status: 429, code: "subscription_exhausted" });
  });

  it("does NOT flag a QUOTA-matching success that has no usage object", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "success", result: "you've reached your position limit" },
    ]));
    const res = await complete({ model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] });
    expect(res.text).toBe("you've reached your position limit");
  });

  it("does NOT flag a QUOTA-matching success whose usage lacks output_tokens", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "success", result: "you've reached your position limit", usage: {} },
    ]));
    const res = await complete({ model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] });
    expect(res.text).toBe("you've reached your position limit");
  });

  it("does NOT flag a real completion that merely mentions rate limits (output tokens > 0)", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "success", result: "The API rate limit is 100/min.",
        usage: { input_tokens: 10, output_tokens: 42 } },
    ]));
    const res = await complete({ model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] });
    expect(res.text).toBe("The API rate limit is 100/min.");
  });

  it("does NOT flag a 0-token completion whose text does not match the quota pattern", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "success", result: "", usage: { input_tokens: 10, output_tokens: 0 } },
    ]));
    const res = await complete({ model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] });
    expect(res.text).toBe("");
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

  it("passes an AbortController into the SDK query options", async () => {
    queryMock.mockReturnValue(sdkStream([
      { type: "result", subtype: "success", result: "ok" },
    ]));
    await complete({ model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] });
    const opts = queryMock.mock.calls[0][0].options;
    expect(opts.abortController).toBeInstanceOf(AbortController);
  });

});

// A stubbed SDK stream that never yields a result and never completes.
function hangingStream() {
  return {
    [Symbol.asyncIterator]() {
      return { next: () => new Promise<never>(() => {}) };
    },
  };
}

describe("complete timeout & abort (#9)", () => {
  it("aborts the query and rejects with a timeout error when no result ever arrives", async () => {
    queryMock.mockReturnValue(hangingStream());
    const start = Date.now();
    await expect(
      complete(
        { model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] },
        { timeoutMs: 50 },
      ),
    ).rejects.toMatchObject({ status: 504, code: "timeout" });
    expect(Date.now() - start).toBeLessThan(2000);
    const ac = queryMock.mock.calls[0][0].options.abortController as AbortController;
    expect(ac.signal.aborted).toBe(true);
  });

  it("aborts promptly and rejects when the incoming signal is already aborted", async () => {
    queryMock.mockReturnValue(hangingStream());
    const signal = AbortSignal.abort();
    await expect(
      complete(
        { model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] },
        { signal, timeoutMs: 60000 },
      ),
    ).rejects.toMatchObject({ status: 499, code: "client_closed" });
    const ac = queryMock.mock.calls[0][0].options.abortController as AbortController;
    expect(ac.signal.aborted).toBe(true);
  });

  it("aborts when the incoming signal fires mid-flight", async () => {
    queryMock.mockReturnValue(hangingStream());
    const controller = new AbortController();
    const p = complete(
      { model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] },
      { signal: controller.signal, timeoutMs: 60000 },
    );
    setTimeout(() => controller.abort(), 20);
    await expect(p).rejects.toMatchObject({ status: 499, code: "client_closed" });
    const ac = queryMock.mock.calls[0][0].options.abortController as AbortController;
    expect(ac.signal.aborted).toBe(true);
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

describe("resultToResponse guard order", () => {
  const ask = () => complete({ model: "claude-opus-4-8", messages: [{ role: "user", content: "hi" }] });
  const result = (extra: Record<string, unknown>) =>
    queryMock.mockReturnValue(sdkStream([{ type: "result", subtype: "success", ...extra }]));

  // --- Stufe 1: Kontingent. Die Erschoepfung traegt api_error_status:429 GENAUSO wie ein
  // transienter Upstream-429 — das Feld trennt sie nicht. Nur der Praefix trennt.
  it("weekly limit stays subscription_exhausted despite api_error_status 429", async () => {
    result({ result: "You've hit your weekly limit · resets 9am (UTC)",
             api_error_status: 429, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 429, code: "subscription_exhausted" });
  });

  it("session limit stays subscription_exhausted", async () => {
    result({ result: "You've hit your session limit · resets 3pm",
             api_error_status: 429, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 429, code: "subscription_exhausted" });
  });

  it("out-of-usage-credits is subscription_exhausted", async () => {
    result({ result: "You're out of usage credits. Run /usage-credits to keep using Opus",
             api_error_status: 429, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 429, code: "subscription_exhausted" });
  });

  // DAS Gatter: dieser Text matcht QUOTA ("not your usage limit"), traegt aber den Praefix.
  // Ohne den Ausschluss oeffnete ein Anthropic-Lastabwurf den globalen Cooldown fuer 1h.
  it("transient 429 with prefix is upstream_api_error, NOT subscription_exhausted", async () => {
    result({ result: "API Error: Server is temporarily limiting requests (not your usage limit) · " +
                     "this may be a temporary capacity issue.",
             api_error_status: 429, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 429, code: "upstream_api_error" });
  });

  it("a real answer merely mentioning limits is not a quota case", async () => {
    result({ result: "You've hit your weekly limit · resets 9am (UTC)",
             api_error_status: 429, is_error: true,
             usage: { input_tokens: 10, output_tokens: 120 } });
    await expect(ask()).rejects.toMatchObject({ status: 429, code: "upstream_api_error" });
  });

  // --- Stufe 2a: Auth. Die CLI baut diese Meldungen ueber `_u`, sie kommen also als
  // ERFOLGS-Result an und erreichen mapSdkError nie.
  it("auth errors keep auth_expired on the success path", async () => {
    result({ result: "Authentication error · Your credentials have expired · Please run /login",
             api_error_status: 401, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 500, code: "auth_expired" });
  });

  // --- Stufe 2b: strukturell
  it("529 reference case (the five prod transcripts)", async () => {
    result({ result: "API Error: 529 Overloaded. This is a server-side issue, usually temporary",
             api_error_status: 529, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 529, code: "upstream_api_error" });
  });

  it("passes a 400 through", async () => {
    result({ result: "API Error: 400 bad request", api_error_status: 400, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 400, code: "upstream_api_error" });
  });

  // Falle: ein truthy-Test statt `!= null` faellt hier durch.
  it("api_error_status 0 clamps to 502 and still throws", async () => {
    result({ result: "weird", api_error_status: 0, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 502, code: "upstream_api_error" });
  });

  it.each([99, 600, 529.5])("clamps implausible api_error_status %p to 502", async (s) => {
    result({ result: "weird", api_error_status: s, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 502 });
  });

  // Die `aie`-Klasse: der String "No response requested." erreicht `result` NIE, er steht in
  // der Filtermenge `_mt`. Sie kommt als "" an — oder als veralteter JSON-Blob aus einem
  // frueheren Turn, und DER geht heute durch die Schema-Pruefung.
  it("is_error with an empty result throws", async () => {
    result({ result: "", api_error_status: 429, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 429, code: "upstream_api_error" });
  });

  it("is_error with stale structured output throws instead of returning a wrong answer", async () => {
    result({ result: '{"symbol":"AAPL","confidence":0.9}', api_error_status: 429, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 429, code: "upstream_api_error" });
  });

  // Klassen ohne Feld UND ohne Praefix — heute stille Falsch-Erfolge.
  it("catches a timeout that carries neither field nor prefix", async () => {
    result({ result: "Request timed out", api_error_status: null, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 502, code: "upstream_api_error" });
  });

  it("catches the high-load message", async () => {
    result({ result: "Opus is experiencing high load, please use /model to switch to Sonnet",
             api_error_status: null, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 502, code: "upstream_api_error" });
  });

  it("catches max_output_tokens (Befund-1 row, verbatim)", async () => {
    result({ result: "API Error: Claude's response exceeded the 16 output token maximum. " +
                     "To configure this behavior, set the CLAUDE_CODE_MAX_OUTPUT_TOKENS environment variable.",
             api_error_status: null, is_error: true,
             usage: { input_tokens: 40, output_tokens: 64, cache_read_input_tokens: 52755 } });
    await expect(ask()).rejects.toMatchObject({ status: 502, code: "upstream_api_error" });
  });

  it("catches the context-window variant", async () => {
    result({ result: "API Error: The model has reached its context window limit.",
             api_error_status: null, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 502, code: "upstream_api_error" });
  });

  it("falls back to the code parsed from the text when the field is null", async () => {
    // Ohne diesen Fall ueberlebt eine Mutation, die den Text-Code-Fallback ganz entfernt.
    result({ result: "API Error: 529 Overloaded.", api_error_status: null, is_error: true,
             usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 529, code: "upstream_api_error" });
  });

  // --- Stufe 3: Regex als Netz (CLI ohne is_error)
  it("falls back to the regex when neither field is present", async () => {
    result({ result: "API Error: 529 Overloaded.", usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 529, code: "upstream_api_error" });
  });

  it("tolerates a leading newline on the regex path", async () => {
    result({ result: "\nAPI Error: 529 Overloaded.", usage: { input_tokens: 0, output_tokens: 0 } });
    await expect(ask()).rejects.toMatchObject({ status: 529 });
  });

  // --- Regression: kein Verhaltenswechsel fuer heute funktionierende Eingaben
  it("passes a real answer containing API Error: mid-text through as success", async () => {
    result({ result: 'Der Report nannte "API Error: 529" als Ursache.',
             usage: { input_tokens: 10, output_tokens: 40 } });
    const res = await ask();
    expect(res.text).toContain("API Error: 529");
    expect(res.usage.output_tokens).toBe(40);
  });

  it("passes a plain answer through untouched", async () => {
    result({ result: "AAPL looks fine.", usage: { input_tokens: 10, output_tokens: 5 } });
    expect((await ask()).text).toBe("AAPL looks fine.");
  });
});
