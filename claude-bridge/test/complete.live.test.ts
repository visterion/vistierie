import { describe, it, expect } from "vitest";
// NOTE: no vi.mock here — this file drives the REAL @anthropic-ai/claude-agent-sdk
// (and the Claude Code CLI child it spawns) against a live Claude subscription.
import { complete } from "../src/complete.js";

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
