package de.vesterion.vistierie.auth;

import de.vesterion.vistierie.tenants.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Optional;

@Component
public class AuthFilter extends OncePerRequestFilter {

    // Spring's own default instance: URL-decodes and normalizes the path the same way
    // Spring MVC's HandlerMapping does when it dispatches. Prefix checks below MUST run
    // against this decoded path, not the raw req.getRequestURI(), otherwise a
    // percent-encoded "/admin/" prefix (e.g. "/%61dmin/tenants") slips past the filter's
    // checks as a non-admin path while still being routed to the admin controller
    // (Finding #10).
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private final TenantRepository tenants;
    private final BCryptPasswordEncoder enc;
    private final String adminTokenHash;
    private final TokenAuthCache cache;

    public AuthFilter(TenantRepository tenants,
                      BCryptPasswordEncoder enc,
                      @Value("${vistierie.admin.token-hash:}") String adminTokenHash,
                      TokenAuthCache cache) {
        this.tenants = tenants;
        this.enc = enc;
        this.adminTokenHash = adminTokenHash;
        this.cache = cache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        var path = PATH_HELPER.getLookupPathForRequest(req);
        if (path.equals("/healthz") || path.equals("/readyz")
                || path.startsWith("/actuator/")) {
            chain.doFilter(req, res);
            return;
        }
        var header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "missing bearer token");
            return;
        }
        var token = header.substring("Bearer ".length()).trim();
        try {
            if (path.startsWith("/admin/")) {
                if (adminTokenHash.isBlank() || !enc.matches(token, adminTokenHash)) {
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "bad admin token");
                    return;
                }
                RequestContext.set(new RequestContext.Principal(null, null, true));
            } else {
                var principal = resolveTenant(token);
                if (principal.isEmpty()) {
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "bad tenant token");
                    return;
                }
                RequestContext.set(principal.get());
            }
            chain.doFilter(req, res);
        } finally {
            RequestContext.clear();
        }
    }

    private Optional<RequestContext.Principal> resolveTenant(String token) {
        var cached = cache.get(token);
        if (cached.isPresent()) {
            return cached.get();
        }
        var matched = tenants.findAll().stream()
                .filter(t -> enc.matches(token, t.tokenHash()))
                .findFirst();
        var principal = matched.map(t -> new RequestContext.Principal(t.id(), t.name(), false));
        cache.put(token, principal);
        return principal;
    }
}
