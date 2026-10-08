import { describe, it, expect } from "vitest";
// NOTE: no vi.mock here — this file drives the REAL @anthropic-ai/claude-agent-sdk
// (and the Claude Code CLI child it spawns) against a live Claude subscription.
import { complete } from "../src/complete.js";
import { SessionStore } from "../src/sessions.js";

// Regression guard for the plain-path maxTurns fix
// (docs/bugs/2026-07-19-claude-bridge-maxturns-plain-path.md).
//
// The unit tests in complete.test.ts mock the SDK, so they PIN the maxTurns *value*
// but can never see a change in the SDK/CLI turn-counting semantics. Only a real call
// catches that. This suite is therefore skipped by default and must be run explicitly
// when bumping @anthropic-ai/claude-agent-sdk and before deploying the bridge:
//
//   BRIDGE_LIVE_TEST=1 CLAUDE_CODE_OAUTH_TOKEN=<subscription token> npm test
//
// It fails if a plain, high-effort completion (the class of call that failed at
// maxTurns:1 and forced a metered fallback) no longer returns a terminal result —
// e.g. because an SDK update made the current maxTurns bound insufficient again.
const LIVE = process.env.BRIDGE_LIVE_TEST === "1" && !!process.env.CLAUDE_CODE_OAUTH_TOKEN;

describe.skipIf(!LIVE)("complete — live subscription (maxTurns regression guard)", () => {
  it(
    "returns a terminal result for a plain high-effort completion (no max_turns abort)",
    async () => {
      const res = await complete(
        {
          model: "claude-haiku-4-5",
          max_tokens: 64,
          effort: "high", // forces the internal thinking turn that tripped maxTurns:1
          system: "Answer in one short sentence.",
          messages: [{ role: "user", content: "Reason briefly, then say what 2+2 is." }],
        },
        { timeoutMs: 90_000 },
      );

      expect(typeof res.text).toBe("string");
      expect(res.text.length).toBeGreaterThan(0);
      expect(res.stop_reason).toBe("end_turn");
    },
    100_000,
  );
});

describe.skipIf(!LIVE)("complete — live subscription (per-turn usage, spec 2026-10-08)", () => {
  it(
    "the first tool turn reports real output tokens and the served model id",
    async () => {
      const store = new SessionStore();
      const res = await complete(
        {
          model: "haiku", // family alias: the bridge must report the RESOLVED id
          max_tokens: 512,
          effort: "off",
          system: "You must call the ping tool exactly once before answering.",
          messages: [{ role: "user", content: "Call the ping tool with value 1." }],
          tools: [
            {
              name: "ping",
              description: "Ping with a number.",
              input_schema: { type: "object", properties: { value: { type: "number" } } },
            },
          ],
        },
        { sessions: store, timeoutMs: 90_000 },
      );
      try {
        expect(res.stop_reason).toBe("tool_use");
        expect(res.usage.output_tokens).toBeGreaterThan(0);
        expect(res.model).toMatch(/^claude-/);
        expect(res.requested_model).toBe("haiku");
      } finally {
        if (res.session_id) store.close(res.session_id);
      }
    },
    100_000,
  );
});
