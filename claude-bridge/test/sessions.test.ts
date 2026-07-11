import { describe, it, expect, vi } from "vitest";
import { SessionStore } from "../src/sessions.js";
function fakeSession() {
  return { abort: new AbortController(),
    iterator: (async function* () {})()[Symbol.asyncIterator](),
    pending: new Map() };
}
describe("SessionStore", () => {
  it("create/take round-trips and touches", () => {
    let t = 0; const store = new SessionStore({ ttlMs: 100, now: () => t });
    const s = store.create(fakeSession());
    t = 90; expect(store.take(s.id)?.id).toBe(s.id);
    t = 180; expect(store.take(s.id)?.id).toBe(s.id); // touched at 90 → still alive
  });
  it("expired sessions are aborted and gone", () => {
    let t = 0; const store = new SessionStore({ ttlMs: 100, now: () => t });
    const s = store.create(fakeSession());
    t = 201; expect(store.take(s.id)).toBeUndefined();
    expect(s.abort.signal.aborted).toBe(true);
  });
  it("cap throws session_limit", () => {
    const store = new SessionStore({ cap: 1 });
    store.create(fakeSession());
    expect(() => store.create(fakeSession())).toThrowError(/session_limit|503/);
  });
  it("close resolves pending tools as errors", async () => {
    const store = new SessionStore({});
    const s = store.create(fakeSession());
    const p = new Promise((resolve) => s.pending.set("tu_1",
      { id: "tu_1", name: "x", resolve }));
    store.close(s.id);
    await expect(p).resolves.toMatchObject({ isError: true });
  });
});
