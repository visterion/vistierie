export interface CompleteRequest {
  model: string;
  max_tokens?: number;
  system?: string | null;
  messages: Array<{ role: string; content: unknown }>;
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
