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
                        // PROD-INT-01: production-source read-only intake lookup.
                        // Service must ALSO require APPOINTMENT_BOOK,
                        // PATIENT_REGISTRATION_READ, both module gates, branch
                        // authorization, tenant RLS and a successful read audit.
                        // No patient write/booking is enabled by this rule.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/branches/{branchId}/opd/intake/{registrationId}")
                            .authenticated()
                        // R1-10A: the appointment admission handoff is a
                        // production-source SERVICE, but its HTTP adapter is
                        // test-only. Do not expose it to dev/prod or patients.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/branches/{branchId}/opd/registration-handoff/{registrationId}")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & registration-candidate & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        .requestMatchers(HttpMethod.POST,"/api/v1/auth/logout","/api/v1/auth/password").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/branches/{branchId}/patient-registrations")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of("test & (c2-synthetic | registration-candidate) & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        // PROD-INT-02: production-source command endpoints
                        // remain DENIED unless explicitly reviewed-activated.
                        // Even when enabled, they need a separately created
                        // restricted SQL writer; never a default test migrator.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/branches/{branchId}/opd/appointments",
                                "/api/v1/branches/{branchId}/opd/appointments/{appointmentId}/check-in")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.getProperty(
                                        "aronexa.opd.booking.reviewed-activation",
                                        Boolean.class,false)
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        // R1-10B3A: distinct protected HTTP-to-restricted-PG17
                        // adapter, present ONLY in a disposable Testcontainers
                        // source test class. This is NEVER a production route.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/branches/{branchId}/opd-restricted/appointments",
                                "/api/v1/branches/{branchId}/opd-restricted/appointments/{appointmentId}/check-in")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & opd-restricted-candidate & !dev & !prod"))
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
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/consultation-draft",
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/consultation-context")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & appointment-candidate & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/consultation-draft")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & appointment-candidate & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        // R1-07 structured prescription DRAFT: TEST ONLY.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/prescription-draft")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & appointment-candidate & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/prescription-draft")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & appointment-candidate & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        // R1-08: all billing/cash/report routes exist ONLY
                        // under disposable Testcontainers candidate profile.
                        // No production invoice issuance or payment endpoint.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/billing-context",
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/bill",
                                "/api/v1/branches/{branchId}/billing/daily")
                            .access((authn, request) -> new org.springframework.security.authorization.AuthorizationDecision(
                                environment.acceptsProfiles(Profiles.of(
                                        "test & appointment-candidate & !dev & !prod"))
                                && authn.get().isAuthenticated()
                                && authn.get().getPrincipal() instanceof AuthPrincipal))
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/bill",
                                "/api/v1/branches/{branchId}/appointments/{appointmentId}/bill/cash")
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
