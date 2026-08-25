package com.dashboard.api.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Authenticates protected endpoints with either a static bearer API key or a Google ID token.
 *
 * <p><b>Fails closed.</b> The previous implementation granted access whenever no API key was
 * configured on the server, reasoning that unconfigured meant "internal call". Combined with
 * Cloud Run's {@code --allow-unauthenticated}, a single failed secret mount silently exposed
 * the full audit log — including client IPs — to the internet. A misconfigured server now
 * rejects requests instead, and says so loudly at startup.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Paths served without credentials. Kept in sync with SecurityConfig's permitAll matchers. */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/swagger-ui", "/v3/api-docs", "/actuator");
    private static final List<String> PUBLIC_EXACT = List.of("/", "/health", "/error");
    private static final List<String> PUBLIC_POSTBACK = List.of(
            "/api/v1/audit/postback", "/audit/postback", "/api/audit/postback");

    private final Set<String> validApiKeys;
    private final String allowedEmail;
    private final GoogleIdTokenVerifier googleVerifier;

    public ApiKeyAuthFilter(@Value("${dashboard.api-key:}") String dashboardApiKey,
                            @Value("${continuum.api-key:}") String continuumApiKey,
                            @Value("${dashboard.allowed-email:}") String allowedEmail) {
        this.validApiKeys = new LinkedHashSet<>();
        addIfPresent(this.validApiKeys, dashboardApiKey);
        addIfPresent(this.validApiKeys, continuumApiKey);
        this.allowedEmail = allowedEmail == null ? "" : allowedEmail.trim();

        this.googleVerifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance()).build();

        if (this.validApiKeys.isEmpty()) {
            log.error("[Auth] No API key configured (API_KEY / CONTINUUM_API_KEY). "
                    + "Authenticated endpoints will reject every request until one is set.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_EXACT.contains(path)
                || PUBLIC_POSTBACK.contains(path)
                || PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = bearerToken(request);
        String principal = token == null ? null : authenticate(token);

        if (principal == null) {
            reject(response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        chain.doFilter(request, response);
    }

    /** @return the authenticated principal name, or {@code null} if the token is not valid. */
    private String authenticate(String token) {
        for (String candidate : validApiKeys) {
            if (constantTimeEquals(candidate, token)) {
                return "api-key-client";
            }
        }
        return verifyGoogleIdToken(token);
    }

    private String verifyGoogleIdToken(String token) {
        try {
            GoogleIdToken idToken = googleVerifier.verify(token);
            if (idToken == null) {
                return null;
            }
            String email = idToken.getPayload().getEmail();
            if (email == null) {
                return null;
            }
            // An empty allow-list means "any verified Google identity", which is almost never
            // intended for a personal service. Require an explicit match.
            if (allowedEmail.isBlank() || !allowedEmail.equalsIgnoreCase(email)) {
                log.warn("[Auth] Rejected valid Google token for non-allowed identity");
                return null;
            }
            return email;
        } catch (Exception e) {
            log.debug("[Auth] Google ID token verification failed", e);
            return null;
        }
    }

    /**
     * Credentials are accepted from the Authorization header only. The previous {@code ?key=}
     * query-parameter fallback wrote the secret into Cloud Run request logs, browser history,
     * and any downstream Referer header.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"Unauthorized\",\"message\":\"Provide a valid API key or Google ID token "
                        + "in the Authorization header.\"}");
    }

    /** Avoids leaking key material through response-time differences on a byte-by-byte compare. */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void addIfPresent(Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }
}
