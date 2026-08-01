import { describe, it, expect } from "vitest";
import { API_ERROR, QUOTA, AUTH, clampApiStatus, apiErrorFrom, mapSdkError } from "../src/errors.js";
import { BridgeError } from "../src/types.js";

describe("mapSdkError", () => {
  it("maps usage-limit errors to 429 subscription_exhausted", () => {
    for (const msg of [
      "Claude AI usage limit reached|1751621999",
      "You've hit your usage limit",
      "Rate limit exceeded, try again later",
    ]) {
      const e = mapSdkError(new Error(msg));
      expect(e.status).toBe(429);
      expect(e.code).toBe("subscription_exhausted");
    }
  });

  it("maps the weekly-limit prose to 429 subscription_exhausted", () => {
    const e = mapSdkError(new Error("You've hit your weekly limit · resets 9am (UTC)"));
    expect(e.status).toBe(429);
    expect(e.code).toBe("subscription_exhausted");
  });

  it("does NOT classify unrelated 'limit' prose as a quota error", () => {
    for (const msg of [
      "the attacker breached your perimeter defense limit",
      "We hit your target. Then a hard limit appeared",
      "reached your goal; the sky is the limit",
    ]) {
      const e = mapSdkError(new Error(msg));
      expect(e.code).not.toBe("subscription_exhausted");
    }
  });

  it("maps auth errors to 500 auth_expired", () => {
    for (const msg of [
      "OAuth token has expired",
      "Invalid bearer token",
      "authentication_error: unauthorized",
    ]) {
      const e = mapSdkError(new Error(msg));
      expect(e.status).toBe(500);
      expect(e.code).toBe("auth_expired");
    }
  });

  it("maps everything else to 500 sdk_error", () => {
    const e = mapSdkError(new Error("process exited with code 1"));
    expect(e.status).toBe(500);
    expect(e.code).toBe("sdk_error");
  });

  it("passes BridgeError through unchanged", () => {
    const orig = new BridgeError(429, "subscription_exhausted", "x");
    expect(mapSdkError(orig)).toBe(orig);
  });
});

describe("API_ERROR", () => {
  // Diese Zusicherung testet keinen Fehlerfall, sie stellt eine Falle: complete.ts benutzt
  // die Konstante mit .test() UND .exec() auf demselben String. Mit /g liefe lastIndex
  // zwischen den beiden Aufrufen weg und Schritt 2b faende den Code nicht mehr.
  it("has no global flag and is stateless across calls", () => {
    expect(API_ERROR.global).toBe(false);
    const s = "API Error: 529 Overloaded.";
    expect(API_ERROR.test(s)).toBe(true);
    expect(API_ERROR.test(s)).toBe(true);
    expect(API_ERROR.exec(s)?.[1]).toBe("529");
    expect(API_ERROR.exec(s)?.[1]).toBe("529");
  });

  it("matches the prefix with a three-digit code", () => {
    expect(API_ERROR.exec("API Error: 529 Overloaded.")?.[1]).toBe("529");
  });

  it("matches the prefix without a code (Befund 1, verbatim prod row)", () => {
    const m = API_ERROR.exec(
      "API Error: Claude's response exceeded the 16 output token maximum. " +
      "To configure this behavior, set the CLAUDE_CODE_MAX_OUTPUT_TOKENS environment variable.");
    expect(m).not.toBeNull();
    expect(m?.[1]).toBeUndefined();
  });

  it("tolerates a leading newline", () => {
    expect(API_ERROR.test("\nAPI Error: 529 Overloaded.")).toBe(true);
  });

  it("does NOT match mid-text", () => {
    expect(API_ERROR.test('Der Report sagt: "API Error: 529" (historisch)')).toBe(false);
  });

  it("drops a four-digit code via the word boundary", () => {
    expect(API_ERROR.exec("API Error: 4290 nonsense")?.[1]).toBeUndefined();
  });
});

describe("QUOTA", () => {
  it("matches the verbatim weekly-limit prose", () => {
    expect(QUOTA.test("You've hit your weekly limit · resets 9am (UTC)")).toBe(true);
  });

  it("matches the session-limit variant", () => {
    expect(QUOTA.test("You've hit your session limit · resets 3pm")).toBe(true);
  });

  // Die CLI schreibt "out of USAGE credits" — die alte Alternative `out of (?:quota|credits)`
  // matcht das nicht. Ohne diese Erweiterung faellt eine echte Erschoepfung durch.
  it("matches the usage-credits variant", () => {
    expect(QUOTA.test("You're out of usage credits. Run /usage-credits to keep using Opus")).toBe(true);
  });

  it("DOES match the transient-429 text — which is exactly why the prefix gate exists", () => {
    // Diese Zusicherung dokumentiert das Problem, nicht die Loesung: der transiente 429
    // matcht QUOTA ("not your usage limit"). Getrennt werden die beiden Klassen erst durch
    // den Praefix-Ausschluss in complete.ts, nicht durch diese Regex.
    expect(QUOTA.test("Server is temporarily limiting requests (not your usage limit)")).toBe(true);
  });
});

describe("clampApiStatus", () => {
  it.each([400, 408, 413, 422, 429, 500, 529, 599])("passes %i through", (n) => {
    expect(clampApiStatus(n)).toBe(n);
  });

  // 529.5 ist die Falle: ein reiner Bereichstest laesst sie durch, und Node wirft dafuer
  // KEINEN RangeError — es schreibt still eine kaputte Statuszeile.
  it.each([0, 99, 399, 600, 529.5, 429.5, "429", NaN, Infinity, null, undefined])(
    "clamps %p to 502", (n) => {
      expect(clampApiStatus(n)).toBe(502);
    });
});

describe("apiErrorFrom", () => {
  it("returns null for a non-API-Error text", () => {
    expect(apiErrorFrom("a perfectly normal answer")).toBeNull();
  });

  it("builds a BridgeError with the parsed code", () => {
    const e = apiErrorFrom("API Error: 529 Overloaded.");
    expect(e).toMatchObject({ status: 529, code: "upstream_api_error" });
  });

  it("falls back to 502 when the code is absent", () => {
    expect(apiErrorFrom("API Error: The model has reached its context window limit."))
      .toMatchObject({ status: 502, code: "upstream_api_error" });
  });

  it("clamps an implausible code to 502", () => {
    expect(apiErrorFrom("API Error: 099 nonsense")).toMatchObject({ status: 502 });
  });
});

describe("mapSdkError ordering", () => {
  it("still maps quota prose to subscription_exhausted", () => {
    expect(mapSdkError(new Error("You've hit your weekly limit · resets 9am (UTC)")))
      .toMatchObject({ status: 429, code: "subscription_exhausted" });
  });

  // Der transiente 429 matcht QUOTA. Ohne den Praefix-Ausschluss wuerde er hier den
  // globalen, tenant-uebergreifenden SubscriptionCooldown oeffnen.
  it("does NOT map the transient 429 to subscription_exhausted", () => {
    const e = mapSdkError(new Error(
      "API Error: Server is temporarily limiting requests (not your usage limit) · " +
      "this may be a temporary capacity issue."));
    expect(e.code).toBe("upstream_api_error");
    expect(e.status).toBe(502); // kein dreistelliger Code im Text
  });

  it("keeps auth_expired ahead of API_ERROR", () => {
    expect(mapSdkError(new Error("API Error: 401 unauthorized — oauth token expired")))
      .toMatchObject({ status: 500, code: "auth_expired" });
  });

  it("maps an API Error text to upstream_api_error with its code", () => {
    expect(mapSdkError(new Error("API Error: 529 Overloaded.")))
      .toMatchObject({ status: 529, code: "upstream_api_error" });
  });

  it("still falls through to sdk_error", () => {
    expect(mapSdkError(new Error("something else entirely")))
      .toMatchObject({ status: 500, code: "sdk_error" });
  });
});
