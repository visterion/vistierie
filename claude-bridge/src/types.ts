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

export interface CompleteResponse {
  text: string;
  stop_reason: string;
  model: string;
  usage: {
    input_tokens: number;
    output_tokens: number;
    cache_creation_input_tokens: number;
    cache_read_input_tokens: number;
  };
  content_blocks?: ContentBlockWire[];
  session_id?: string;
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
