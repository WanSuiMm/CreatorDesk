package org.creatordesk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OwnerRegistry owners, ObjectMapper mapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, ignored) -> {
                    response.setStatus(401);
                    response.setContentType("application/json; charset=utf-8");
                    mapper.writeValue(response.getOutputStream(), Map.of("error", Map.of(
                            "code", "authentication_required", "message", "Authentication is required.")));
                }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/healthz", "/actuator/health", "/actuator/health/**", "/", "/index.html", "/app.js", "/styles.css").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new DeskFilter(owners, mapper), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static final class DeskFilter extends OncePerRequestFilter {
        private static final int BODY_LIMIT = 128 * 1024;
        private static final java.util.regex.Pattern LOCAL_HOST = java.util.regex.Pattern.compile(
                "(?i)^(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?$");
        private final OwnerRegistry owners;
        private final ObjectMapper mapper;

        DeskFilter(OwnerRegistry owners, ObjectMapper mapper) {
            this.owners = owners;
            this.mapper = mapper;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            String host = request.getHeader(HttpHeaders.HOST);
            if (host == null || !LOCAL_HOST.matcher(host).matches()) {
                fail(response, 400, "invalid_host", "Only the local CreatorDesk origin is accepted.");
                return;
            }
            if (request.getRequestURI().equals("/") || request.getRequestURI().equals("/index.html")
                    || request.getRequestURI().equals("/app.js")
                    || request.getRequestURI().equals("/styles.css")) {
                response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; "
                        + "connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            }
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (origin != null && !origin.equals("http://" + host)) {
                fail(response, 403, "cross_origin_denied", "Cross-origin requests are not accepted.");
                return;
            }
            HttpServletRequest bounded = request;
            if (hasJsonBody(request)) {
                long length = request.getContentLengthLong();
                if (length > BODY_LIMIT) {
                    fail(response, 413, "body_too_large", "The request body is too large.");
                    return;
                }
                byte[] body = request.getInputStream().readNBytes(BODY_LIMIT + 1);
                if (body.length > BODY_LIMIT) {
                    fail(response, 413, "body_too_large", "The request body is too large.");
                    return;
                }
                bounded = new ReplayableRequest(request, body);
            }
            if (requiresBearer(request.getRequestURI())) {
                Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
                if (headers == null || !headers.hasMoreElements()) {
                    fail(response, 401, "authentication_required", "Authentication is required.");
                    return;
                }
                String auth = headers.nextElement();
                if (headers.hasMoreElements() || auth == null || !auth.startsWith("Bearer ") || auth.length() > 4103) {
                    fail(response, 401, "authentication_required", "Authentication is required.");
                    return;
                }
                OwnerConfig owner = owners.authenticate(auth.substring(7));
                if (owner == null) {
                    fail(response, 401, "authentication_required", "Authentication is required.");
                    return;
                }
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(owner.owner(), null, List.of()));
            }
            try { chain.doFilter(bounded, response); }
            finally { SecurityContextHolder.clearContext(); }
        }

        private static boolean hasJsonBody(HttpServletRequest request) {
            String method = request.getMethod();
            return (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))
                    && request.getContentLengthLong() != 0;
        }

        private static boolean requiresBearer(String path) {
            return !(path.equals("/healthz") || path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                    || path.equals("/") || path.equals("/index.html") || path.equals("/app.js") || path.equals("/styles.css"));
        }

        private void fail(HttpServletResponse response, int status, String code, String message) throws IOException {
            response.setStatus(status);
            response.setContentType("application/json; charset=utf-8");
            mapper.writeValue(response.getOutputStream(), Map.of("error", Map.of("code", code, "message", message)));
        }
    }

    private static final class ReplayableRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        ReplayableRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
