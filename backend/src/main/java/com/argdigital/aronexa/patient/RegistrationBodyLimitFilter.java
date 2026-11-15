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
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Actual-stream registration cap; registered only by explicitly bounded adapters. */
class RegistrationBodyLimitFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 4096;
    private static final PathPattern REGISTRATION_PATH = new PathPatternParser()
            .parse("/api/v1/branches/{branchId}/patient-registrations");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if(!"POST".equals(request.getMethod())) return true;
        // Use the same decoded-segment semantics as Spring MVC. Raw URI regex
        // matching lets encoded branch OR literal-route characters evade caps.
        var path=ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parseAndCache(request);
        return !REGISTRATION_PATH.matches(path.pathWithinApplication());
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
        ServletInputStream incoming = request.getInputStream();
        while ((bytesRead = incoming.read(buffer)) != -1) {
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
