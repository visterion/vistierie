package de.vesterion.vistierie.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, TTL in-process cache mapping a SHA-256 hash of a presented bearer token to the
 * resolved {@link RequestContext.Principal} for that token — or, for unknown/invalid tokens,
 * a cached "no match" (negative) result.
 *
 * <p>This exists to avoid re-running the O(n) BCrypt scan over every tenant on every request
 * (Finding #2): a positive lookup is cached for {@code positiveTtlSeconds}, a negative lookup
 * (which would otherwise let repeated invalid tokens keep re-scanning all tenants) for the
 * shorter {@code negativeTtlSeconds}.
 *
 * <p>The deployment is single-instance, so this in-process cache is sufficient — no
 * cross-instance invalidation is needed. Callers that mutate tenant auth state (create,
 * delete, kill, clear-kill) must call {@link #clear()} to avoid serving a stale mapping.
 */
@Component
public class TokenAuthCache {

    // Simplest correct eviction policy: once the bound is hit, drop one arbitrary entry
    // (the first one the map's iterator yields) to make room. Admin mutations are rare and
    // trigger a full flush anyway, so this only needs to bound memory from varied bad tokens.
    private static final int MAX_ENTRIES = 10_000;

    private final long positiveTtlMillis;
    private final long negativeTtlMillis;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public TokenAuthCache(
            @Value("${vistierie.auth.cache.positive-ttl-seconds:60}") long positiveTtlSeconds,
            @Value("${vistierie.auth.cache.negative-ttl-seconds:10}") long negativeTtlSeconds) {
        this.positiveTtlMillis = positiveTtlSeconds * 1000;
        this.negativeTtlMillis = negativeTtlSeconds * 1000;
    }

    /**
     * @return empty if there is no live cache entry for this token (caller must resolve it
     *         the slow way); otherwise an Optional wrapping the cached result, which is
     *         itself empty for a cached "no match".
     */
    public Optional<Optional<RequestContext.Principal>> get(String token) {
        var entry = entries.get(hash(token));
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            entries.remove(hash(token), entry);
            return Optional.empty();
        }
        return Optional.of(entry.principal());
    }

    public void put(String token, Optional<RequestContext.Principal> principal) {
        var key = hash(token);
        if (!entries.containsKey(key) && entries.size() >= MAX_ENTRIES) {
            evictOne();
        }
        var ttlMillis = principal.isPresent() ? positiveTtlMillis : negativeTtlMillis;
        entries.put(key, new Entry(principal, Instant.now().plusMillis(ttlMillis)));
    }

    /** Full flush. Called on tenant auth-state mutations (create/delete/kill/clear-kill). */
    public void clear() {
        entries.clear();
    }

    private void evictOne() {
        Iterator<String> it = entries.keySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; this cannot happen in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record Entry(Optional<RequestContext.Principal> principal, Instant expiresAt) {}
}
