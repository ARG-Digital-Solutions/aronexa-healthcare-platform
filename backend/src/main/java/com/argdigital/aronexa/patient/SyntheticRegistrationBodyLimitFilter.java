package com.argdigital.aronexa.patient;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Isolated C2 synthetic-registration ingress cap only. Enforced before MVC
 * deserializes JSON. Not a substitute for reverse-proxy/header/timeouts or a
 * production admission-control design. No request body or credentials logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile("test & c2-synthetic & !dev & !prod")
final class SyntheticRegistrationBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 4096;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod())
                || !path.matches("/api/v1/branches/[0-9a-fA-F-]{36}/patient-registrations");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            tooLarge(response);
            return;
        }
        // Count actual streamed bytes, not only potentially absent or
        // dishonest Content-Length. Never buffer more than MAX_BODY_BYTES.
        ByteArrayOutputStream limited = new ByteArrayOutputStream(256);
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = request.getInputStream().read(buffer)) != -1) {
            if (bytesRead > MAX_BODY_BYTES - limited.size()) {
                tooLarge(response);
                return;
            }
            limited.write(buffer, 0, bytesRead);
        }
        byte[] body = limited.toByteArray();
        HttpServletRequestWrapper repeatable = new HttpServletRequestWrapper(request) {
            @Override
            public int getContentLength() { return body.length; }

            @Override
            public long getContentLengthLong() { return body.length; }

            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream source = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override
                    public int read() { return source.read(); }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        return source.read(b, off, len);
                    }

                    @Override
                    public boolean isFinished() { return source.available() == 0; }

                    @Override
                    public boolean isReady() { return true; }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        // Synchronous MVC request body consumption only.
                        throw new UnsupportedOperationException("No async body listener");
                    }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(
                        getInputStream(), StandardCharsets.UTF_8));
            }
        };
        chain.doFilter(repeatable, response);
    }

    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"REGISTRATION_PAYLOAD_TOO_LARGE\"}");
    }
}
