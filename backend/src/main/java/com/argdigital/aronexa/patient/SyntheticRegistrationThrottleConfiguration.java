package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Test-only, before @RequestBody parsing: malformed attempts must also count.
 * Not multi-instance safe, no reverse-proxy identity assumptions, not for prod.
 */
@Configuration(proxyBeanMethods = false)
@Profile("test & c2-synthetic & !dev & !prod")
class SyntheticRegistrationThrottleConfiguration implements WebMvcConfigurer {
    private final SyntheticRegistrationAttemptLimiter limiter =
            SyntheticRegistrationAttemptLimiter.syntheticDefaults();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) throws IOException {
                if (!"POST".equals(request.getMethod())) return true;
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                // Existing security filter handles 401/403 without giving any
                // anonymous principal an ability to reset or consume a quota.
                if (auth == null || !auth.isAuthenticated()
                        || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
                    return true;
                }
                if (limiter.allow(principal.tenantId(), principal.membershipId())) {
                    return true;
                }
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setHeader("Cache-Control", "no-store");
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"REGISTRATION_RATE_LIMITED\"}");
                return false;
            }
        }).addPathPatterns("/api/v1/branches/*/patient-registrations");
    }
}
