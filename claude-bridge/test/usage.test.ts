import { describe, it, expect } from "vitest";
import { UsageAccumulator, usageFrom, zeroUsage } from "../src/usage.js";

const ev = (event: Record<string, unknown>) => ({ type: "stream_event", parent_tool_use_id: null, event });
const start = (id: string, model = "claude-opus-5-5") =>
  ev({ type: "message_start", message: { id, model, usage: { input_tokens: 2, output_tokens: 1, cache_creation_input_tokens: 7, cache_read_input_tokens: 9 } } });
const delta = (usage: Record<string, unknown>) => ev({ type: "message_delta", delta: { stop_reason: null }, usage });
const stop = () => ev({ type: "message_stop" });
const U = (i: number, o: number, cw: number, cr: number) =>
  ({ input_tokens: i, output_tokens: o, cache_creation_input_tokens: cw, cache_read_input_tokens: cr });

describe("UsageAccumulator", () => {
  it("reports the final delta of a closed message", () => {
    const acc = new UsageAccumulator();
    for (const m of [start("m1"), delta(U(4, 290, 3984, 28101)), stop()]) acc.observe(m);
    expect(acc.takeTurn()).toEqual({ usage: U(4, 290, 3984, 28101), model: "claude-opus-5-5", closed: 1 });
  });

  it("a later delta for the same open message replaces, never adds", () => {
    const acc = new UsageAccumulator();
    for (const m of [start("m1"), delta(U(1, 10, 0, 0)), delta(U(1, 40, 0, 0)), stop()]) acc.observe(m);
    expect(acc.takeTurn().usage).toEqual(U(1, 40, 0, 0));
  });

  it("fills fields the delta lacks from message_start", () => {
    const acc = new UsageAccumulator();
    for (const m of [start("m1"), delta({ output_tokens: 50 }), stop()]) acc.observe(m);
    expect(acc.takeTurn().usage).toEqual(U(2, 50, 7, 9));
  });

  it("ignores assistant snapshot usage (E3) and every other message type", () => {
    const acc = new UsageAccumulator();
    acc.observe({ type: "assistant", message: { id: "m1", usage: U(2, 6, 0, 0), content: [] } });
    acc.observe({ type: "user", message: { content: [] } });
    acc.observe(ev({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "x" } }));
    expect(acc.takeTurn()).toEqual({ usage: zeroUsage(), model: undefined, closed: 0 });
  });

  it("sums two messages, reports the last model and resets after takeTurn", () => {
    const acc = new UsageAccumulator();
    for (const m of [
      start("m1", "claude-opus-5-5"), delta(U(2, 120, 3984, 14000)), stop(),
      start("m2", "claude-sonnet-5-5"), delta(U(2, 170, 0, 14101)), stop(),
    ]) acc.observe(m);
    expect(acc.takeTurn()).toEqual({ usage: U(4, 290, 3984, 28101), model: "claude-sonnet-5-5", closed: 2 });
    expect(acc.takeTurn().closed).toBe(0);
  });

  it("tracks the open state and force-closes with the usage seen so far", () => {
    const acc = new UsageAccumulator();
    acc.observe(start("m1"));
    expect(acc.isOpen()).toBe(true);
    acc.observe(delta(U(1, 5, 0, 0)));
    acc.closeOpen();
    expect(acc.isOpen()).toBe(false);
    expect(acc.takeTurn().usage).toEqual(U(1, 5, 0, 0));
    acc.observe(stop()); // a late stop with nothing open is ignored
    expect(acc.takeTurn().closed).toBe(0);
  });

  it("a closed message without any delta contributes zero but counts as closed", () => {
    const acc = new UsageAccumulator();
    for (const m of [start("m1"), stop()]) acc.observe(m);
    expect(acc.takeTurn()).toEqual({ usage: zeroUsage(), model: "claude-opus-5-5", closed: 1 });
  });

  it("hasEverClosed stays true after takeTurn clears the closed messages", () => {
    const acc = new UsageAccumulator();
    expect(acc.hasEverClosed()).toBe(false);
    acc.observe(start("m1"));
    expect(acc.hasEverClosed()).toBe(false); // open is not closed
    acc.observe(stop());
    expect(acc.hasEverClosed()).toBe(true);
    acc.takeTurn();
    expect(acc.hasEverClosed()).toBe(true);
  });

  it("hasEverClosed is set by a forced closeOpen too", () => {
    const acc = new UsageAccumulator();
    acc.observe(start("m1"));
    acc.closeOpen();
    expect(acc.hasEverClosed()).toBe(true);
  });

  it("claimMissingLog is true exactly once", () => {
    const acc = new UsageAccumulator();
    expect(acc.claimMissingLog()).toBe(true);
    expect(acc.claimMissingLog()).toBe(false);
  });
});

describe("usageFrom", () => {
  it("defaults missing or invalid fields to 0", () => {
    expect(usageFrom({ input_tokens: 10, output_tokens: -1 })).toEqual(U(10, 0, 0, 0));
    expect(usageFrom(undefined)).toEqual(zeroUsage());
  });
});
