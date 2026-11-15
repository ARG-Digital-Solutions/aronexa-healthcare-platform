package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Default-off single-instance boundary. No credentials or patient payload retained. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="aronexa.patient.registration.reviewed-activation",
        havingValue="true",matchIfMissing=false)
@Profile("!c2-synthetic & !registration-candidate")
class RegistrationAdmissionConfiguration implements WebMvcConfigurer {
    private final RegistrationAttemptLimiter limiter=RegistrationAttemptLimiter.singleInstanceDefaults();

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    RegistrationBodyLimitFilter registrationBodyLimitFilter() {
        return new RegistrationBodyLimitFilter();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request,HttpServletResponse response,
                    Object handler) throws IOException {
                if(!"POST".equals(request.getMethod())) return true;
                var auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth==null || !auth.isAuthenticated()
                        || !(auth.getPrincipal() instanceof AuthPrincipal actor)) return true;
                // MVC interceptors run before @RequestBody deserialization:
                // malformed JSON must consume the same authenticated quota.
                if(limiter.allow(actor.tenantId(),actor.membershipId())) return true;
                response.setStatus(429);
                response.setHeader("Retry-After","60");
                response.setHeader("Cache-Control","no-store");
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"REGISTRATION_RATE_LIMITED\"}");
                return false;
            }
        }).addPathPatterns("/api/v1/branches/*/patient-registrations");
    }
}
