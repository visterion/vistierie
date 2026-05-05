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

import java.io.IOException;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final TenantRepository tenants;
    private final BCryptPasswordEncoder enc;
    private final String adminTokenHash;

    public AuthFilter(TenantRepository tenants,
                      BCryptPasswordEncoder enc,
                      @Value("${vistierie.admin.token-hash:}") String adminTokenHash) {
        this.tenants = tenants;
        this.enc = enc;
        this.adminTokenHash = adminTokenHash;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        var path = req.getRequestURI();
        if (path.equals("/healthz") || path.equals("/readyz")) {
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
                var matched = tenants.findAll().stream()
                        .filter(t -> enc.matches(token, t.tokenHash()))
                        .findFirst();
                if (matched.isEmpty()) {
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "bad tenant token");
                    return;
                }
                var t = matched.get();
                RequestContext.set(new RequestContext.Principal(t.id(), t.name(), false));
            }
            chain.doFilter(req, res);
        } finally {
            RequestContext.clear();
        }
    }
}
