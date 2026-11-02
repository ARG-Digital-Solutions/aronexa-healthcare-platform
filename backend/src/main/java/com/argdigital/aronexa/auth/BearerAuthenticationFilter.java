package com.argdigital.aronexa.auth;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Stateless database-backed opaque bearer token, no cookies or Java HTTP session. */
final class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService service;
    BearerAuthenticationFilter(AuthService service) { this.service = service; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getMethod().equals("POST") &&
                request.getRequestURI().substring(request.getContextPath().length()).equals("/api/v1/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            if (!header.startsWith("Bearer ")) {
                response.sendError(401); return;
            }
            try {
                AuthPrincipal principal = service.authenticate(header.substring(7));
                // Forced password changes cannot be bypassed for future clinical routes.
                if (principal.mustChangePassword() &&
                        !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/auth/")) {
                    response.sendError(403); return;
                }
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal,null,
                                principal.permissions().stream()
                                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                                        .toList()));
            } catch (AuthenticationRejected e) {
                SecurityContextHolder.clearContext();
                response.sendError(401); return;
            }
        }
        try { chain.doFilter(request,response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
