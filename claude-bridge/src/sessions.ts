import { randomUUID } from "node:crypto";
import { BridgeError } from "./types.js";

export interface PendingTool {
  id: string;
  name: string;
  resolve: (result: { content: unknown; isError: boolean }) => void;
}

export interface Session {
  id: string;
  abort: AbortController;
  iterator: AsyncIterator<Record<string, any>>;
  pending: Map<string, PendingTool>;
  createdAt: number;
  touchedAt: number;
}

export interface SessionStoreOptions {
  ttlMs?: number;
  maxLifetimeMs?: number;
  cap?: number;
  now?: () => number;
}

export class SessionStore {
  private sessions = new Map<string, Session>();
  private ttlMs: number;
  private maxLifetimeMs: number;
  private cap: number;
  private now: () => number;

  constructor(opts?: SessionStoreOptions) {
    this.ttlMs =
      opts?.ttlMs ?? (Number(process.env.BRIDGE_SESSION_TTL_MS) || 300000);
    this.maxLifetimeMs = opts?.maxLifetimeMs ?? 1_800_000;
    this.cap = opts?.cap ?? 32;
    this.now = opts?.now ?? (() => Date.now());
  }

  create(
    s: Omit<Session, "id" | "createdAt" | "touchedAt">
  ): Session {
    if (this.sessions.size >= this.cap) {
      throw new BridgeError(
        503,
        "session_limit",
        `session_limit: cap (${this.cap}) reached`
      );
    }

    const now = this.now();
    const session: Session = {
      id: randomUUID(),
      abort: s.abort,
      iterator: s.iterator,
      pending: s.pending,
      createdAt: now,
      touchedAt: now,
    };

    this.sessions.set(session.id, session);
    return session;
  }

  take(id: string): Session | undefined {
    const session = this.sessions.get(id);
    if (!session) {
      return undefined;
    }

    const now = this.now();
    const isExpiredByTTL = now - session.touchedAt > this.ttlMs;
    const isExpiredByLifetime = now - session.createdAt > this.maxLifetimeMs;

    if (isExpiredByTTL || isExpiredByLifetime) {
      this.removeSession(session);
      return undefined;
    }

    // Touch the session
    session.touchedAt = now;
    return session;
  }

  close(id: string): void {
    const session = this.sessions.get(id);
    if (session) {
      this.removeSession(session);
    }
  }

  sweep(): void {
    const now = this.now();
    const toRemove: Session[] = [];

    for (const session of this.sessions.values()) {
      const isExpiredByTTL = now - session.touchedAt > this.ttlMs;
      const isExpiredByLifetime = now - session.createdAt > this.maxLifetimeMs;

      if (isExpiredByTTL || isExpiredByLifetime) {
        toRemove.push(session);
      }
    }

    for (const session of toRemove) {
      this.removeSession(session);
    }
  }

  size(): number {
    return this.sessions.size;
  }

  private removeSession(session: Session): void {
    this.sessions.delete(session.id);
    session.abort.abort();

    // Resolve all pending tools with error
    const errorResult = { content: { error: "session closed" }, isError: true };
    for (const pending of session.pending.values()) {
      pending.resolve(errorResult);
    }
    session.pending.clear();
  }
}
