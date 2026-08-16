import { randomUUID } from "node:crypto";
import { query, createSdkMcpServer, tool } from "@anthropic-ai/claude-agent-sdk";
import type { Options, SDKUserMessage } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import {
  BridgeError,
  EFFORT_VALUES,
  type ContentBlockWire,
  type CompleteRequest,
  type CompleteResponse,
  type ToolDefWire,
} from "./types.js";
import type { PendingTool, Session, SessionRuntime, SessionStore, ToolResult } from "./sessions.js";
import { API_ERROR, AUTH, QUOTA, apiErrorFrom, clampApiStatus, mapSdkError } from "./errors.js";

/**
 * Flatten the (opaque) Vistierie message history into one content-block list
 * for a single synthesized user turn. Assistant turns are prefixed so the
 * model can distinguish them; image blocks pass through untouched.
 */
export function flattenMessages(
  messages: CompleteRequest["messages"],
): Array<Record<string, unknown>> {
  const blocks: Array<Record<string, unknown>> = [];
  for (const m of messages) {
    const prefix = m.role === "assistant" ? "[assistant]\n" : "";
    if (typeof m.content === "string") {
      blocks.push({ type: "text", text: prefix + m.content });
    } else if (Array.isArray(m.content)) {
      for (const block of m.content as Array<Record<string, unknown>>) {
        if (block.type === "text") {
          blocks.push({ type: "text", text: prefix + String(block.text) });
        } else if (block.type === "tool_use") {
          blocks.push({
            type: "text",
            text: `${prefix}[tool_use ${String(block.id)}] ${String(block.name)} ${JSON.stringify(block.input ?? {})}`,
          });
        } else if (block.type === "tool_result") {
          blocks.push({
            type: "text",
            text: `${prefix}[tool_result ${String(block.tool_use_id)}] ${JSON.stringify(block.content ?? null)}`,
          });
        } else {
          blocks.push(block);
        }
      }
    } else if (m.content !== null && m.content !== undefined) {
      blocks.push({ type: "text", text: prefix + String(m.content) });
    }
  }
  return blocks;
}

/** Options controlling a single {@link complete} call. */
export interface CompleteOptions {
  /**
   * Abort signal tied to the incoming HTTP request. When it fires (client
   * disconnect / Java-side timeout), the in-flight SDK query is aborted so the
   * spawned CLI child process is torn down instead of leaking.
   */
  signal?: AbortSignal;
  /**
   * Hard upper bound (ms) on consuming the SDK query. On expiry the query is
   * aborted and a 504 timeout error is thrown. Defaults to
   * `BRIDGE_QUERY_TIMEOUT_MS` (or 290000, just under the Java 300s read timeout
   * so the bridge gives up first and cleans up).
   */
  timeoutMs?: number;
  /**
   * Session store for tool mode. Required when the request carries `tools` or a
   * `session_id`; unused for plain completions.
   */
  sessions?: SessionStore;
}

function resolveTimeoutMs(override?: number): number {
  if (typeof override === "number") return override;
  const fromEnv = Number(process.env.BRIDGE_QUERY_TIMEOUT_MS);
  return Number.isFinite(fromEnv) && fromEnv > 0 ? fromEnv : 290000;
}

function zeroUsage(): CompleteResponse["usage"] {
  return {
    input_tokens: 0,
    output_tokens: 0,
    cache_creation_input_tokens: 0,
    cache_read_input_tokens: 0,
  };
}

/** Apply effort / max_tokens knobs shared by plain and tool paths. */
function applyModelKnobs(options: Options, req: CompleteRequest): void {
  if (req.effort === "off") {
    options.thinking = { type: "disabled" };
  } else if (req.effort !== undefined) {
    options.effort = req.effort;
  }
  // The Claude Agent SDK query Options type exposes no direct per-query
  // output-token cap, but the bundled CLI honors CLAUDE_CODE_MAX_OUTPUT_TOKENS,
  // so we forward req.max_tokens through the child process env.
  if (req.max_tokens !== undefined) {
    options.env = { ...process.env, CLAUDE_CODE_MAX_OUTPUT_TOKENS: String(req.max_tokens) };
  }
}

/** Map an SDK `result` message to the wire response (throws on error subtype).
 *
 * With `structuredToolName` set, the caller asked for structured output: the validated
 * payload comes back as the single `tool_use` block callers already parse, so the wire shape
 * matches the other providers. The error checks below stay in front of it — a structured
 * payload must never mask an exhausted subscription or an expired token. */
function resultToResponse(
  msg: Record<string, any>,
  model: string,
  structuredToolName?: string,
): CompleteResponse {
  if (msg.subtype === "success") {
    const text = String(msg.result ?? "");
    const outputTokens = msg.usage?.output_tokens ?? 0;

    // 1. Kontingent-Erschoepfung. Neu ist NUR der Praefix-Ausschluss: der transiente 429
    //    der CLI traegt "API Error:" UND matcht QUOTA (sein Text enthaelt "not your usage
    //    limit"), die echte Erschoepfung traegt den Praefix nie. Ohne den Ausschluss
    //    oeffnet ein Anthropic-Lastabwurf den globalen, tenant-uebergreifenden Cooldown.
    //    Die Erschoepfung traegt api_error_status:429 genauso wie der transiente Fall —
    //    das Feld taugt hier NICHT als Diskriminator.
    if (!API_ERROR.test(text) && msg.usage?.output_tokens === 0 && QUOTA.test(text)) {
      throw new BridgeError(429, "subscription_exhausted", text);
    }

    // 2a. Auth behaelt seinen eigenen Code. Die CLI baut die Auth-Meldungen ueber `_u`,
    //     sie kommen also als ERFOLGS-Result mit is_error:true an — nicht ueber
    //     mapSdkError. Status 500 (nicht 401), damit shouldFallback (429 || >= 500)
    //     greift: ein abgelaufenes Token soll auf Bedrock ausweichen, nicht den Run toeten.
    if ((msg.api_error_status != null || msg.is_error === true) && AUTH.test(text)) {
      throw new BridgeError(500, "auth_expired", text);
    }

    // 2b. Alle uebrigen API-Fehler, strukturell. `is_error` ist auf SDKResultSuccess nicht
    //     optional und deckt auch die Klassen ohne Feld und ohne Praefix ab (Timeout,
    //     High-Load, leeres Result). `is_error === true` gilt genau dann, wenn der
    //     zurueckgegebene Text DER FEHLERTEXT ist — beide Result-Builder der CLI leiten
    //     Flag und Text aus derselben Message ab. Eine echte Antwort kann hier nicht
    //     gekapert werden.
    if (msg.api_error_status != null || msg.is_error === true) {
      const m = API_ERROR.exec(text);
      throw new BridgeError(
        clampApiStatus(msg.api_error_status ?? (m?.[1] ? Number(m[1]) : undefined)),
        "upstream_api_error", text);
    }

    // 3. Regex allein — Sicherheitsnetz fuer CLI-Versionen ohne `is_error`.
    const fromText = apiErrorFrom(text);
    if (fromText) throw fromText;

    const usage = {
      input_tokens: msg.usage?.input_tokens ?? 0,
      output_tokens: outputTokens,
      cache_creation_input_tokens: msg.usage?.cache_creation_input_tokens ?? 0,
      cache_read_input_tokens: msg.usage?.cache_read_input_tokens ?? 0,
    };

    if (structuredToolName !== undefined) {
      // `subtype: "success"` with no structured_output is real upstream behaviour
      // (claude-agent-sdk-typescript#277), so the FIELD is the gate, never the subtype.
      // 502 is >= 500, so Vistierie fails over to a provider that can force tools; returning
      // an empty block instead would silently hand the caller an empty result. The gate is a
      // SHAPE check, not just nullishness: `{}`, `""`, `0`, `false` and `[]` all pass a
      // nullish check yet none of them can carry the caller's schema, so each becomes a
      // `tool_use` block whose expected fields are simply missing — a silently empty result,
      // the only failure mode here that produces a wrong answer instead of a fallback.
      const structured = asNode(msg.structured_output);
      if (!structured || Object.keys(structured).length === 0) {
        throw new BridgeError(
          502,
          "structured_output_missing",
          text || "result carried no structured_output",
        );
      }
      const block: ContentBlockWire = {
        type: "tool_use",
        id: `structured_${randomUUID()}`,
        name: structuredToolName,
        input: structured,
      };
      return { text: "", stop_reason: "tool_use", model, usage, content_blocks: [block] };
    }

    return { text, stop_reason: "end_turn", model, usage };
  }
  // SDKResultError carries its error text in `errors: string[]` (no `result` field).
  const errorText =
    Array.isArray(msg.errors) && msg.errors.length > 0
      ? msg.errors.join("; ")
      : String(msg.subtype);
  throw mapSdkError(new Error(errorText));
}

/**
 * The tool a request wants FORCED, or null when this is not a structured-output request.
 *
 * Forcing one named tool is how a caller says "give me a single structured answer", not "run
 * an agent loop". The Agent SDK cannot force a tool at all, but it can enforce a JSON Schema
 * through `outputFormat` — so these requests take the plain path with that schema, which
 * needs no MCP server and parks no session. Parked sessions are what leaked a CLI child per
 * call and drove the timeouts this route exists to remove.
 *
 * Deliberately narrow. Anything else — no tool_choice, another choice type, an unknown tool
 * name, a tool without an object schema, more than one tool on the request, or a request
 * continuing a session — returns null and keeps today's behaviour. Vistierie's AgentRunner
 * passes toolChoice = null, so genuine agentic runs never land here.
 */
function structuredToolFrom(req: CompleteRequest): ToolDefWire | null {
  if (req.session_id !== undefined) return null;
  const choice = req.tool_choice;
  if (!choice || choice.type !== "tool" || !choice.name) return null;

  // A forced single-tool call offers exactly one tool by definition. A multi-tool request
  // that forces one of them is the agentic shape and must not divert here — e.g.
  // AgentRunner.java:250-256 appends `submit_result` to an agent's own tools; forcing that
  // tool would otherwise land on this route and never offer the agent's real tools to the
  // model at all. It would not crash — it would silently return a schema-valid payload
  // produced without ever calling a tool.
  if ((req.tools?.length ?? 0) !== 1) {
    console.log(
      `structured-output route declined: tool_choice names "${choice.name}" but request carries ${req.tools?.length ?? 0} tool(s)`,
    );
    return null;
  }
  const named = req.tools!.find((t) => t.name === choice.name);
  if (!named) {
    console.log(`structured-output route declined: tool_choice names unknown tool "${choice.name}"`);
    return null;
  }
  // The bridge validates nothing about request bodies (server.ts checks only `model` and
  // `messages`), so `input_schema` is arbitrary caller JSON. A stringified or array schema
  // would otherwise become `outputFormat.schema` verbatim, handing the CLI something it was
  // never typed to accept.
  if (!asNode(named.input_schema)) {
    console.log(
      `structured-output route declined: tool "${named.name}" has a non-object input_schema`,
    );
    return null;
  }
  return named;
}

export async function complete(
  req: CompleteRequest,
  opts: CompleteOptions = {},
): Promise<CompleteResponse> {
  if (req.effort !== undefined && !EFFORT_VALUES.includes(req.effort)) {
    throw new BridgeError(
      400,
      "invalid_request",
      `effort must be one of ${EFFORT_VALUES.join(", ")}`,
    );
  }

  const structuredTool = structuredToolFrom(req);
  if (structuredTool) return completePlain(req, opts, structuredTool);

  const isToolMode = (req.tools?.length ?? 0) > 0 || req.session_id !== undefined;
  if (isToolMode) return completeTool(req, opts);
  return completePlain(req, opts);
}

// ---------------------------------------------------------------------------
// Plain mode (unchanged behavior): single turn, no tools, no session.
// ---------------------------------------------------------------------------

async function completePlain(
  req: CompleteRequest,
  opts: CompleteOptions,
  structuredTool?: ToolDefWire,
): Promise<CompleteResponse> {
  const content = flattenMessages(req.messages);
  const timeoutMs = resolveTimeoutMs(opts.timeoutMs);

  async function* promptStream(): AsyncGenerator<SDKUserMessage> {
    yield {
      type: "user",
      message: { role: "user", content },
      parent_tool_use_id: null,
      session_id: "",
    } as unknown as SDKUserMessage;
  }

  // We own this controller and hand it to the SDK via `options.abortController`;
  // aborting it stops the query and tears down the spawned CLI child process.
  const controller = new AbortController();

  // A single rejection source for every non-completion outcome (timeout /
  // client disconnect). Whoever fires first also aborts the controller.
  let failWith!: (err: BridgeError) => void;
  const failure = new Promise<never>((_, reject) => {
    failWith = reject;
  });
  const abortWith = (err: BridgeError) => {
    if (!controller.signal.aborted) controller.abort();
    failWith(err);
  };

  const timer = setTimeout(
    () => abortWith(new BridgeError(504, "timeout", `SDK query exceeded ${timeoutMs}ms`)),
    timeoutMs,
  );

  const clientAbort = () =>
    abortWith(new BridgeError(499, "client_closed", "request aborted by client"));
  if (opts.signal) {
    if (opts.signal.aborted) clientAbort();
    else opts.signal.addEventListener("abort", clientAbort, { once: true });
  }

  const options: Options = {
    model: req.model,
    systemPrompt: req.system ?? "",
    // Plain completions have no tools (allowedTools: []), so the model cannot take
    // agentic tool turns — the only turns the SDK/CLI consumes are the model's own
    // thinking/effort steps. maxTurns:1 was too low: reasoning/high-effort completions
    // spend the single turn thinking and abort before emitting the result ("Reached
    // maximum number of turns (1)"), which surfaced as a 502 and forced a metered
    // fallback. A small bound clears the thinking steps without enabling any loop.
    // See docs/bugs/2026-07-19-claude-bridge-maxturns-plain-path.md
    maxTurns: 8,
    allowedTools: [],
    settingSources: [],
    abortController: controller,
  };
  applyModelKnobs(options, req);
  if (structuredTool) {
    // maxTurns stays at the plain path's 8: structured output spends one extra synthetic
    // turn (claude-agent-sdk-python#1013, measured num_turns = 2), well inside that bound.
    options.outputFormat = {
      type: "json_schema",
      schema: structuredTool.input_schema as Record<string, unknown>,
    };
    // `allowedTools: []` above is only the auto-allow list; without `tools: []` the built-ins
    // (Read/Bash/Glob/WebSearch/...) would still be OFFERED to the model, which would attempt
    // one, get denied, and burn turns against maxTurns: 8. Mirrors startSession's `tools: []`
    // (complete.ts) for the same reason. Scoped to this branch only — existing plain-path
    // traffic (no structured tool) is unaffected.
    options.tools = [];
  }

  async function consume(): Promise<CompleteResponse> {
    const q = query({ prompt: promptStream(), options });
    for await (const msg of q as AsyncIterable<Record<string, any>>) {
      if (msg.type !== "result") continue;
      return resultToResponse(msg, req.model, structuredTool?.name);
    }
    throw new BridgeError(500, "no_result", "SDK stream ended without a result message");
  }

  try {
    return await Promise.race([consume(), failure]);
  } catch (err) {
    throw mapSdkError(err);
  } finally {
    clearTimeout(timer);
    opts.signal?.removeEventListener("abort", clientAbort);
  }
}

// ---------------------------------------------------------------------------
// Tool mode: start / continue / replay over a parked SDK session.
// ---------------------------------------------------------------------------

/**
 * The SDK exposes in-process MCP tools to the model as
 * `mcp__<serverName>__<toolName>`, and assistant tool_use blocks come back with
 * that fully qualified name. The Java caller only knows the bare tool name, so
 * every observed tool_use name is normalized by stripping this prefix — both in
 * the content_blocks returned on the wire and in what the FIFO matcher
 * registers (MCP handlers park under the bare name).
 */
const MCP_PREFIX = "mcp__vistierie__";

function stripPrefix(name: string): string {
  return name.startsWith(MCP_PREFIX) ? name.slice(MCP_PREFIX.length) : name;
}

interface Slot {
  promise: Promise<ToolResult>;
  resolve: (r: ToolResult) => void;
}

/**
 * FIFO matcher pairing MCP tool-handler invocations with observed tool_use
 * blocks, per tool name. Handler invocation and assistant-message observation
 * race each other, so each side lazily creates the next slot; whichever arrives
 * second finds the slot already there. `registerPending` also records the slot
 * in the session's `pending` map (keyed by tool_use_id) so the continue call
 * can resolve it and so session teardown can fail it.
 */
function createMatcher(pending: Map<string, PendingTool>): SessionRuntime["matcher"] {
  const queues = new Map<string, { slots: Slot[]; handlerIdx: number; registerIdx: number }>();

  function queueFor(name: string) {
    let e = queues.get(name);
    if (!e) {
      e = { slots: [], handlerIdx: 0, registerIdx: 0 };
      queues.set(name, e);
    }
    return e;
  }

  function slotAt(e: { slots: Slot[] }, idx: number): Slot {
    while (e.slots.length <= idx) {
      let resolve!: (r: ToolResult) => void;
      const promise = new Promise<ToolResult>((r) => {
        resolve = r;
      });
      e.slots.push({ promise, resolve });
    }
    return e.slots[idx];
  }

  return {
    takeHandlerSlot(name: string): Promise<ToolResult> {
      const e = queueFor(name);
      return slotAt(e, e.handlerIdx++).promise;
    },
    registerPending(block: { id: string; name: string }): void {
      const e = queueFor(block.name);
      const slot = slotAt(e, e.registerIdx++);
      pending.set(block.id, { id: block.id, name: block.name, resolve: slot.resolve });
    },
  };
}

/** A JSON Schema node, as far as anything can be assumed about it off the wire. */
type SchemaNode = Record<string, unknown>;

function asNode(node: unknown): SchemaNode | undefined {
  return node && typeof node === "object" && !Array.isArray(node) ? (node as SchemaNode) : undefined;
}

/**
 * An `enum` as a union of its members, or `undefined` if the members are not
 * usable (mixed types, or a non-scalar member) — the caller then uses `z.any()`.
 *
 * The member values ARE pinned: they are what the model most needs to see, and
 * they can no longer cause a rejection because every top-level property is made
 * total with `.catch(undefined)` in `deriveShape`. `null` among the members
 * becomes a `z.null()` branch (mirroring `type: ["string", "null"]`).
 */
function deriveEnumType(values: unknown[]): z.ZodTypeAny | undefined {
  if (values.length === 0) return undefined;
  const members: z.ZodTypeAny[] = [];
  const kinds = new Set<string>();
  for (const v of values) {
    if (v === null) {
      members.push(z.null());
      continue;
    }
    const t = typeof v;
    if (t !== "string" && t !== "number" && t !== "boolean") return undefined;
    kinds.add(t);
    members.push(z.literal(v as string | number | boolean));
  }
  if (kinds.size > 1) return undefined;
  if (members.length === 1) return members[0];
  return z.union(members as [z.ZodTypeAny, z.ZodTypeAny, ...z.ZodTypeAny[]]);
}

/**
 * Translate one JSON Schema node into a Zod type, recursively.
 *
 * This types what is ADVERTISED to the model; it never gates a call. Arguments
 * reach Vistierie verbatim from the assistant block, and Vistierie validates the
 * authoritative JSON Schema after the call — that is the only enforcement point.
 * `deriveShape` makes every top-level property total with `.catch(undefined)`,
 * so nothing derived here can be rejected by the SDK's pre-handler validation.
 *
 * Objects stay "loose" (unknown keys survive) and their members optional
 * (`required` is not enforced), so a payload is never rejected for a missing or
 * extra key. Anything unrecognised, missing or malformed degrades to `z.any()`;
 * deriving a shape must never throw and never break tool construction.
 */
function deriveType(node: unknown): z.ZodTypeAny {
  const s = asNode(node);
  if (!s) return z.any();

  if (Array.isArray(s.enum)) {
    const fromEnum = deriveEnumType(s.enum);
    if (fromEnum) return fromEnum;
    return z.any();
  }

  // `type` may be a list of alternatives, e.g. ["string", "null"].
  if (Array.isArray(s.type)) {
    const members = s.type.map((t) => deriveType({ ...s, type: t }));
    if (members.length === 0) return z.any();
    if (members.length === 1) return members[0];
    return z.union(members as [z.ZodTypeAny, z.ZodTypeAny, ...z.ZodTypeAny[]]);
  }

  switch (s.type) {
    case "object": {
      const props = asNode(s.properties);
      // No declared properties: accept any object rather than an empty one.
      if (!props) return z.record(z.string(), z.any());
      return z.looseObject(
        Object.fromEntries(Object.entries(props).map(([k, v]) => [k, deriveType(v).optional()])),
      );
    }
    case "array":
      return z.array(deriveType(s.items));
    case "string":
      return z.string();
    case "number":
      return z.number();
    case "integer":
      // Advertised as "integer"; a fractional value still reaches the handler
      // because the top-level property is total (see `deriveShape`).
      return z.int();
    case "boolean":
      return z.boolean();
    case "null":
      return z.null();
    default:
      return z.any();
  }
}

/**
 * Derive a Zod raw shape from a JSON Schema's top-level properties. The SDK's
 * `inputSchema` accepts a Zod raw shape only, not JSON Schema, so without this
 * the tool would be advertised untyped and the model has to guess the structure
 * (observed in production: an array-of-objects property sent as a JSON-encoded
 * string). The full contract is still appended to the tool description for the
 * constraints Zod cannot express.
 *
 * NOTE — every top-level property stays `.optional()` on purpose. This function
 * types arguments, it does not gate them: Vistierie validates the real JSON
 * Schema (`required` included) server-side, so enforcing requiredness here would
 * add a second, differently-behaving gate and could reject payloads that pass
 * today. Please do not "fix" it.
 *
 * NOTE — `.catch(undefined)` on each property is what makes validation TOTAL,
 * and it must stay exactly here. The SDK builds `z.object(shape)` from this and
 * validates tool input against it BEFORE the handler runs (`validateToolInput`),
 * while `pump` has already emitted the `tool_use` block and registered it as
 * pending. A rejection therefore advances only the register counter of the FIFO
 * matcher — a permanent off-by-one for that tool name: Vistierie runs the call
 * anyway, the model retries on "Invalid arguments" and the tool executes a
 * second time. With a `.catch()` on every property, no property failure can fail
 * the object, so that path is unreachable.
 *
 * Placement is load-bearing in the other direction too: it sits AFTER
 * `.optional()` at the top level only, and never inside `deriveType`. Measured
 * on zod 4.4.3, `z.toJSONSchema(t, {io:"input"})` of `x.optional().catch(undefined)`
 * is byte-identical to that of `x.optional()`, nested types included — the
 * advertised array items, object members, enum members and `"integer"` all
 * survive. A `.catch()` that ever flattened the schema to `{}` would silently
 * reintroduce the production bug it was added for (a model sending a nested
 * array as a JSON string), so `derive-shape.test.ts` asserts both halves.
 */
export function deriveShape(inputSchema: unknown): Record<string, z.ZodTypeAny> {
  try {
    const props = asNode(asNode(inputSchema)?.properties);
    if (!props) return {};
    return Object.fromEntries(
      Object.entries(props).map(([k, v]) => {
        try {
          return [k, deriveType(v).optional().catch(undefined)];
        } catch {
          return [k, z.any().optional().catch(undefined)];
        }
      }),
    );
  } catch {
    return {};
  }
}

function buildTool(def: ToolDefWire, matcher: SessionRuntime["matcher"]) {
  const shape = deriveShape(def.input_schema);
  const description =
    (def.description ?? def.name) +
    "\nInput schema (JSON Schema): " +
    JSON.stringify(def.input_schema ?? {});
  return tool(def.name, description, shape, async () => {
    // Never executes anything: park until the continue call resolves the slot.
    const result = await matcher.takeHandlerSlot(def.name);
    const text = typeof result.content === "string" ? result.content : JSON.stringify(result.content);
    return { content: [{ type: "text", text }], isError: result.isError };
  });
}

async function completeTool(
  req: CompleteRequest,
  opts: CompleteOptions,
): Promise<CompleteResponse> {
  const store = opts.sessions;
  if (!store) {
    throw new BridgeError(500, "sdk_error", "tool mode requires a session store");
  }

  if (req.session_id) {
    const session = store.take(req.session_id);
    if (session) {
      return continueSession(req, opts, store, session);
    }
    // Unknown/expired session: transparent replay from the full flattened
    // history (Task 1 renders tool blocks as text), treated as a fresh start.
    console.log(`session replay for ${req.session_id}`);
  }

  return startSession(req, opts, store);
}

function startSession(
  req: CompleteRequest,
  opts: CompleteOptions,
  store: SessionStore,
): Promise<CompleteResponse> {
  const content = flattenMessages(req.messages);
  const controller = new AbortController();
  const pending = new Map<string, PendingTool>();
  const matcher = createMatcher(pending);

  const toolDefs = req.tools ?? [];
  const mcpServer = createSdkMcpServer({
    name: "vistierie",
    version: "1.0.0",
    tools: toolDefs.map((t) => buildTool(t, matcher)),
  });

  const options: Options = {
    model: req.model,
    systemPrompt: req.system ?? "",
    maxTurns: 100,
    settingSources: [],
    mcpServers: { vistierie: mcpServer },
    // `tools: []` disables ALL built-in tools (availability filter); MCP tools
    // from `mcpServers` are unaffected. `allowedTools` is only the permission
    // auto-allow list, so both are needed: without `tools: []` the built-ins
    // would still be offered to the model.
    tools: [],
    allowedTools: toolDefs.map((t) => `${MCP_PREFIX}${t.name}`),
    abortController: controller,
  };
  applyModelKnobs(options, req);

  // Keep the input stream open: yield the first turn, then await a promise that
  // only settles on session close, so the CLI doesn't end the session early.
  let closeInput!: () => void;
  const inputClosed = new Promise<void>((resolve) => {
    closeInput = resolve;
    controller.signal.addEventListener("abort", () => resolve(), { once: true });
  });
  async function* promptStream(): AsyncGenerator<SDKUserMessage> {
    yield {
      type: "user",
      message: { role: "user", content },
      parent_tool_use_id: null,
      session_id: "",
    } as unknown as SDKUserMessage;
    await inputClosed;
  }

  const q = query({ prompt: promptStream(), options });
  const iterator = (q as AsyncIterable<Record<string, any>>)[Symbol.asyncIterator]();

  let session: Session;
  try {
    session = store.create({
      abort: controller,
      iterator,
      pending,
      runtime: { matcher, closeInput },
    });
  } catch (err) {
    // At cap (or any create failure): abort so the spawned CLI child is torn down.
    controller.abort();
    throw err;
  }

  return runWithGuards(req, opts, store, session);
}

function continueSession(
  req: CompleteRequest,
  opts: CompleteOptions,
  store: SessionStore,
  session: Session,
): Promise<CompleteResponse> {
  // Reject concurrent continues before touching any pending state: one iterator
  // must never be pumped by two HTTP calls at once.
  if (session.busy) {
    throw new BridgeError(
      409,
      "session_busy",
      `session ${session.id} already has a request in flight`,
    );
  }

  const last = req.messages[req.messages.length - 1];
  const results = Array.isArray(last?.content)
    ? (last.content as Array<Record<string, unknown>>).filter((b) => b.type === "tool_result")
    : [];

  // Validate-then-apply: reject unknown ids before resolving anything, so a bad
  // continue leaves the parked session intact for a corrected retry.
  for (const block of results) {
    if (!session.pending.has(String(block.tool_use_id))) {
      throw new BridgeError(
        400,
        "invalid_request",
        `unknown tool_use_id ${String(block.tool_use_id)}`,
      );
    }
  }
  for (const block of results) {
    const id = String(block.tool_use_id);
    session.pending.get(id)!.resolve({ content: block.content, isError: !!block.is_error });
    session.pending.delete(id);
  }

  return runWithGuards(req, opts, store, session);
}

/**
 * Consume the parked SDK stream one message at a time. Returns a tool_use
 * response (leaving the session live) when the model calls tools, or the final
 * end_turn response (closing the session) on a `result` message.
 */
async function pump(
  req: CompleteRequest,
  store: SessionStore,
  session: Session,
): Promise<CompleteResponse> {
  for (;;) {
    const { value: msg, done } = await session.iterator.next();
    if (done) {
      store.close(session.id);
      throw new BridgeError(500, "no_result", "SDK stream ended without a result message");
    }
    if (msg.type === "assistant") {
      // Strip the MCP server prefix from tool_use names; all other blocks
      // (text, thinking, ...) pass through untouched.
      const blocks = ((msg.message?.content ?? []) as ContentBlockWire[]).map((b) =>
        b.type === "tool_use" ? { ...b, name: stripPrefix(String(b.name)) } : b,
      );
      const toolUses = blocks.filter((b) => b.type === "tool_use");
      if (toolUses.length > 0) {
        for (const tu of toolUses) {
          session.runtime!.matcher.registerPending({ id: String(tu.id), name: String(tu.name) });
        }
        return {
          text: "",
          stop_reason: "tool_use",
          model: req.model,
          usage: zeroUsage(),
          content_blocks: blocks,
          session_id: session.id,
        };
      }
      // Assistant text with no tool_use (e.g. interstitial thinking) — keep going.
    }
    if (msg.type === "result") {
      session.runtime!.closeInput();
      store.close(session.id);
      return resultToResponse(msg, req.model);
    }
  }
}

/**
 * Apply the per-HTTP-call timeout + client-disconnect race around a pump. On
 * any failure (timeout, client disconnect, or the pump itself erroring) the
 * session is closed (aborting the query and failing any parked handlers) — a
 * dead iterator must not linger in the store, so the caller's next attempt
 * takes the replay path instead. On a successful tool_use return the session
 * stays live. Marks the session busy for the duration (see continueSession).
 */
async function runWithGuards(
  req: CompleteRequest,
  opts: CompleteOptions,
  store: SessionStore,
  session: Session,
): Promise<CompleteResponse> {
  const timeoutMs = resolveTimeoutMs(opts.timeoutMs);

  let failWith!: (err: BridgeError) => void;
  const failure = new Promise<never>((_, reject) => {
    failWith = reject;
  });
  const fail = (err: BridgeError) => {
    store.close(session.id); // aborts session.abort + fails parked handlers
    failWith(err);
  };

  const timer = setTimeout(
    () => fail(new BridgeError(504, "timeout", `SDK query exceeded ${timeoutMs}ms`)),
    timeoutMs,
  );

  const clientAbort = () =>
    fail(new BridgeError(499, "client_closed", "request aborted by client"));
  if (opts.signal) {
    if (opts.signal.aborted) clientAbort();
    else opts.signal.addEventListener("abort", clientAbort, { once: true });
  }

  session.busy = true;
  const pumping = pump(req, store, session);
  // If the failure branch wins the race, the losing pump promise may still
  // reject later (e.g. the aborted iterator throws) — swallow that so it never
  // surfaces as an unhandledRejection.
  pumping.catch(() => {});
  try {
    return await Promise.race([pumping, failure]);
  } catch (err) {
    // Pump errors (rejected iterator, SDK error result) as well as
    // timeout/disconnect must not leave a dead session in the store.
    store.close(session.id);
    throw mapSdkError(err);
  } finally {
    session.busy = false;
    clearTimeout(timer);
    opts.signal?.removeEventListener("abort", clientAbort);
  }
}
