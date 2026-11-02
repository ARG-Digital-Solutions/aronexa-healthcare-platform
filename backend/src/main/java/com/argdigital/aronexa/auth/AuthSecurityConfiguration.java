package com.argdigital.aronexa.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth) throws Exception {
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
                        .requestMatchers(HttpMethod.POST,"/api/v1/auth/logout","/api/v1/auth/password").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req,res,exc) -> res.sendError(401)))
                .addFilterBefore(new BearerAuthenticationFilter(auth),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
