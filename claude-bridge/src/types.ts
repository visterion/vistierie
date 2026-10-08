export const EFFORT_VALUES = ["off", "low", "medium", "high", "max"] as const;
export type Effort = (typeof EFFORT_VALUES)[number];

export interface ToolDefWire {
  name: string;
  description?: string;
  input_schema?: unknown;
}

/**
 * Anthropic-shaped tool choice as Vistierie forwards it. Only `{type:"tool", name}` is acted
 * upon (see `structuredToolFrom`); other shapes are accepted and ignored, so adding `any` or
 * `auto` later needs no wire change.
 */
export interface ToolChoiceWire {
  type: string;
  name?: string;
}

export interface CompleteRequest {
  model: string;
  max_tokens?: number;
  system?: string | null;
  effort?: Effort;
  messages: Array<{ role: string; content: unknown }>;
  tools?: ToolDefWire[];
  tool_choice?: ToolChoiceWire;
  session_id?: string;
}

export interface ContentBlockWire {
  type: string;
  [k: string]: unknown;
}

/**
 * Claude Max subscription quota as of the session's latest `rate_limit_event` (the CLI emits
 * one only when the info changes). Utilisations are fractions 0..1, null when not reported.
 */
export interface RateLimitWire {
  status: string;
  five_hour_utilization: number | null;
  seven_day_utilization: number | null;
}

export interface CompleteResponse {
  text: string;
  stop_reason: string;
  /** Served model: `message_start` model of the turn's last API message, else the request's. */
  model: string;
  /** The model string the caller sent (`req.model`), echoed verbatim. */
  requested_model: string;
  usage: {
    input_tokens: number;
    output_tokens: number;
    cache_creation_input_tokens: number;
    cache_read_input_tokens: number;
  };
  content_blocks?: ContentBlockWire[];
  session_id?: string;
  /** Absent when no `rate_limit_event` was seen in this request/session. */
  rate_limit?: RateLimitWire;
}

export class BridgeError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}
