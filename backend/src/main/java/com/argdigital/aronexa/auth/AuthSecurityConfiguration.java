package com.argdigital.aronexa.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Default deny. No HTTP Basic, browser session, auto-created password or public registration. */
@Configuration(proxyBeanMethods = false)
class AuthSecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth,
                                            Environment environment) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST,"/api/v1/auth/login").permitAll()
                        // Until P01-T05-C explicitly authorizes a resource, deny its HTTP route.
                        .requestMatchers(HttpMethod.GET,"/api/v1/auth/me").authenticated()
                        // A narrow read-only registration UUID lookup. The service
                        // also requires PATIENT_REGISTRATION_READ, active module,
                        // assigned branch and patient SQL RLS, with audit on success.
                        // This does not authorize the registration POST route.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/branches/{branchId}/patient-registrations/{registrationId}")
                            .authenticated()
                        .requestMatchers(HttpMethod.POST,"/api/v1/auth/logout","/api/v1/auth/password").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/branches/{branchId}/patient-registrations")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of("test & (c2-synthetic | registration-candidate) & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        // R1-04 candidate only: these routes have NO production
                        // controller and stay denied unless the specific disposable
                        // HTTP+PostgreSQL test profile is active. No dev/prod grant.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/branches/{branchId}/appointments",
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/check-in",
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/reschedule",
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/cancel")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & appointment-candidate & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}",
                                "/api/v1/branches/{branchId}/appointments")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & appointment-candidate & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        .anyRequest().denyAll())
                // Sending servlet errors can redispatch /error without the
                // original request's SecurityContext and rewrite an authenticated
                // 403 denial as 401. Return explicit statuses with no error
                // dispatch; the deny-all policy and bearer checks are unchanged.
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req,res,exc) -> {
                            res.setStatus(401);
                            res.setHeader("Cache-Control", "no-store");
                        })
                        .accessDeniedHandler((req,res,exc) -> {
                            res.setStatus(403);
                            res.setHeader("Cache-Control", "no-store");
                        }))
                .addFilterBefore(new BearerAuthenticationFilter(auth),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
