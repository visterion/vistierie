package de.vesterion.vistierie.auth;

import java.util.UUID;

public final class RequestContext {
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    public record Principal(UUID tenantId, String tenantName, boolean admin) {}

    public static void set(Principal p) { CURRENT.set(p); }
    public static Principal get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    public static UUID requireTenantId() {
        var p = CURRENT.get();
        if (p == null || p.tenantId() == null) {
            throw new AuthExceptions.Unauthorized("no tenant context");
        }
        return p.tenantId();
    }

    public static String requireTenantName() {
        var p = CURRENT.get();
        if (p == null || p.tenantName() == null) {
            throw new AuthExceptions.Unauthorized("no tenant context");
        }
        return p.tenantName();
    }

    private RequestContext() {}
}
