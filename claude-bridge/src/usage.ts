import type { CompleteResponse, RateLimitWire } from "./types.js";

export type WireUsage = CompleteResponse["usage"];

const USAGE_FIELDS = [
  "input_tokens",
  "output_tokens",
  "cache_creation_input_tokens",
  "cache_read_input_tokens",
] as const;

export function zeroUsage(): WireUsage {
  return {
    input_tokens: 0,
    output_tokens: 0,
    cache_creation_input_tokens: 0,
    cache_read_input_tokens: 0,
  };
}

function count(v: unknown): number | undefined {
  return typeof v === "number" && Number.isFinite(v) && v >= 0 ? v : undefined;
}

/** Wire usage from an SDK usage object; missing or invalid fields count as 0. */
export function usageFrom(raw: unknown): WireUsage {
  const u = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const out = zeroUsage();
  for (const f of USAGE_FIELDS) out[f] = count(u[f]) ?? 0;
  return out;
}

function asObj(v: unknown): Record<string, unknown> | undefined {
  return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

function fraction(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

/**
 * `rate_limit_info` → wire. `unifiedWindows` is NOT in the SDK type (sdk.d.ts SDKRateLimitInfo
 * only has `utilization` + `rateLimitType`), but the CLI 2.1.293 sends it (spec E5), so prefer it
 * and fall back to the top-level `utilization`, mapped by `rateLimitType`.
 */
export function parseRateLimit(info: unknown): RateLimitWire | undefined {
  const i = asObj(info);
  if (!i) return undefined;
  const windows = asObj(i.unifiedWindows);
  let five = fraction(asObj(windows?.five_hour)?.utilization);
  let seven = fraction(asObj(windows?.seven_day)?.utilization);
  const top = fraction(i.utilization);
  const type = typeof i.rateLimitType === "string" ? i.rateLimitType : "";
  if (top !== null) {
    if (five === null && type === "five_hour") five = top;
    if (seven === null && type.startsWith("seven_day")) seven = top;
  }
  return {
    status: typeof i.status === "string" ? i.status : "unknown",
    five_hour_utilization: five,
    seven_day_utilization: seven,
  };
}

interface OpenMessage {
  model?: string;
  start: Record<string, unknown>;
  final?: WireUsage;
}

interface ClosedMessage {
  model?: string;
  usage: WireUsage;
}

/** What one HTTP turn reports: Σ final usage of the messages closed since the last report. */
export interface TurnUsage {
  usage: WireUsage;
  /** `message_start` model of the turn's last closed message; undefined if none closed. */
  model?: string;
  closed: number;
}

/**
 * Per-API-message usage from the SDK's stream events (`includePartialMessages`).
 *
 * The CLI streams `message_start` (id, served model) → content → `message_delta` (the message's
 * FINAL usage) → `message_stop`. Delta and stop carry no id: they belong to the message opened
 * by the latest `message_start`. Assistant-message snapshot usage is a streaming snapshot (its
 * output_tokens is wrong, and one id is emitted once per content block) and is never read here.
 * Measured on CLI 2.1.293: Σ deltas == result.usage exactly (spec §2, E2/E3).
 */
export class UsageAccumulator {
  private open: OpenMessage | null = null;
  private closed: ClosedMessage[] = [];
  private missingLogged = false;
  private everClosed = false;
  private rateLimit: RateLimitWire | undefined;

  observe(msg: Record<string, any>): void {
    if (msg?.type === "rate_limit_event") {
      const rl = parseRateLimit(msg.rate_limit_info);
      if (rl) this.rateLimit = rl; // the latest event wins; kept across turns
      return;
    }
    if (msg?.type !== "stream_event") return;
    const ev = msg.event as Record<string, any> | undefined;
    switch (ev?.type) {
      case "message_start": {
        const m = (ev.message ?? {}) as Record<string, any>;
        this.open = {
          model: typeof m.model === "string" && m.model ? m.model : undefined,
          start: (m.usage ?? {}) as Record<string, unknown>,
        };
        return;
      }
      case "message_delta": {
        if (!this.open) return;
        const d = (ev.usage ?? {}) as Record<string, unknown>;
        const final = zeroUsage();
        for (const f of USAGE_FIELDS) final[f] = count(d[f]) ?? count(this.open.start[f]) ?? 0;
        this.open.final = final; // a later delta replaces, never adds
        return;
      }
      case "message_stop":
        this.closeOpen();
        return;
      default:
        return;
    }
  }

  /** A `message_start` has been seen without its `message_stop`. */
  isOpen(): boolean {
    return this.open !== null;
  }

  /** Close the open message with the usage seen so far (no-op when nothing is open). */
  closeOpen(): void {
    if (!this.open) return;
    this.closed.push({ model: this.open.model, usage: this.open.final ?? zeroUsage() });
    this.open = null;
    this.everClosed = true;
  }

  /** Any message has ever closed on this accumulator (survives {@link takeTurn}). */
  hasEverClosed(): boolean {
    return this.everClosed;
  }

  /** Report and clear the messages closed since the previous call. */
  takeTurn(): TurnUsage {
    const usage = zeroUsage();
    let model: string | undefined;
    for (const c of this.closed) {
      for (const f of USAGE_FIELDS) usage[f] += c.usage[f];
      if (c.model) model = c.model;
    }
    const closed = this.closed.length;
    this.closed = [];
    return { usage, model, closed };
  }

  /** True exactly once per accumulator — gates the `usage_events_missing` log line. */
  claimMissingLog(): boolean {
    if (this.missingLogged) return false;
    this.missingLogged = true;
    return true;
  }

  /** The session's latest rate-limit info, undefined when no event was seen. */
  latestRateLimit(): RateLimitWire | undefined {
    return this.rateLimit;
  }
}
