import { describe, it, expect } from "vitest";
import { mapSdkError } from "../src/errors.js";
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
