package de.vesterion.vistierie.provider;

/**
 * Claude Max subscription quota as of the bridge's last {@code rate_limit_event} in the session
 * (spec §3.1.5). Utilisations are fractions 0..1, each null when the CLI did not report it.
 * The CLI emits the event only when the info changes, so this is "as of the last change".
 */
public record RateLimitInfo(String status, Double fiveHourUtilization, Double sevenDayUtilization) {}
