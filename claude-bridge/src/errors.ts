import { BridgeError } from "./types.js";

// Erweitert um `(?:usage )?credits`: die CLI schreibt "out of USAGE credits"
// (Binary `cey()`), was die alte Alternative nicht matchte.
export const QUOTA =
  /usage limit|rate.?limit|limit reached|weekly limit|\b(?:reached|hit) your [^.;\n]{0,40}limit|out of (?:quota|(?:usage )?credits)/i;

// Neu EXPORTIERT (bisher modulprivat): complete.ts braucht sie, weil die CLI ihre
// Auth-Meldungen ueber `_u` baut und sie damit als ERFOLGS-Result ankommen, nicht
// ueber den Error-Subtype-Pfad.
export const AUTH = /oauth|bearer token|token.*(expired|invalid)|invalid.*token|authentication|unauthorized/i;

/**
 * Struktureller CLI-Praefix. Der dreistellige Code ist OPTIONAL — es gibt Fehler ohne
 * (`max_output_tokens`, `model_context_window_exceeded`). `^\s*` statt `^`, weil ein
 * fuehrender Zeilenumbruch den Anker sonst lautlos aushebelt. `\b` verwirft vierstellige
 * Codes.
 *
 * NIEMALS das `g`-Flag setzen: complete.ts benutzt diese Konstante mit .test() und .exec()
 * auf demselben String; `lastIndex` wuerde zwischen beiden weglaufen.
 */
export const API_ERROR = /^\s*API Error:(?:\s*(\d{3})\b)?/;

/**
 * 400-599 ganzzahlig durchreichen, alles andere auf 502 — schuetzt `res.status()` in
 * server.ts:34. `Number.isInteger` ist Pflicht: Node wirft bei einem Float IN range keinen
 * RangeError, sondern schreibt eine kaputte Statuszeile (`529.5` -> `status 529`). Ein
 * reiner Bereichstest wuerde `529.5` also still durchlassen.
 */
export function clampApiStatus(n: unknown): number {
  return typeof n === "number" && Number.isInteger(n) && n >= 400 && n <= 599 ? n : 502;
}

/** Gemeinsamer Helfer fuer beide Pfade — ohne ihn wuerde die Clamp-Logik zweimal entstehen. */
export function apiErrorFrom(text: string): BridgeError | null {
  const m = API_ERROR.exec(text);
  if (!m) return null;
  return new BridgeError(clampApiStatus(m[1] ? Number(m[1]) : undefined),
                         "upstream_api_error", text);
}

export function mapSdkError(err: unknown): BridgeError {
  if (err instanceof BridgeError) return err;
  const msg = err instanceof Error ? err.message : String(err);
  // Reihenfolge spiegelt resultToResponse: QUOTA behaelt den Vortritt, aber gegated durch
  // den Praefix-Ausschluss — sonst reisst der transiente 429 (dessen Text "not your usage
  // limit" enthaelt und damit QUOTA matcht) hier dieselbe Cooldown-Luecke auf.
  if (!API_ERROR.test(msg) && QUOTA.test(msg)) {
    return new BridgeError(429, "subscription_exhausted", msg);
  }
  // AUTH VOR API_ERROR: sonst verliert ein `API Error: 401 … unauthorized` seinen
  // auth_expired-Code. Am Status aendert das nichts (beide landen ueber die
  // Provider-Whitelist auf 502), aber das Dashboard-Signal ginge verloren.
  if (AUTH.test(msg)) return new BridgeError(500, "auth_expired", msg);
  const apiErr = apiErrorFrom(msg);
  if (apiErr) return apiErr;
  return new BridgeError(500, "sdk_error", msg);
}
