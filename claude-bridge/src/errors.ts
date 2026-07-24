import { BridgeError } from "./types.js";

export const QUOTA = /usage limit|rate.?limit|limit reached|weekly limit|\b(?:reached|hit) your [^.;\n]{0,40}limit|out of (?:quota|credits)/i;
const AUTH = /oauth|bearer token|token.*(expired|invalid)|invalid.*token|authentication|unauthorized/i;

export function mapSdkError(err: unknown): BridgeError {
  if (err instanceof BridgeError) return err;
  const msg = err instanceof Error ? err.message : String(err);
  if (QUOTA.test(msg)) return new BridgeError(429, "subscription_exhausted", msg);
  if (AUTH.test(msg)) return new BridgeError(500, "auth_expired", msg);
  return new BridgeError(500, "sdk_error", msg);
}
