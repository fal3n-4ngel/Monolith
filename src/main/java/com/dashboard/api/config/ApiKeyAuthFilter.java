package com.dashboard.api.config;

import com.dashboard.api.security.AuthenticatedClient;
import com.dashboard.api.security.ClientKeyMap;
import com.dashboard.api.security.ClientRegistry;
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
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Authenticates protected endpoints with either a static bearer API key or a Google ID token,
 * and resolves which client the credential belongs to.
 *
 * <p><b>Fails closed.</b> A missing API key does not fall back to open access — combined with
 * Cloud Run's {@code --allow-unauthenticated}, that would have meant a single failed secret
 * mount silently exposing every authenticated endpoint to the internet. A misconfigured server
 * rejects requests instead, and says so loudly at startup.
 *
 * <p><b>Credentials carry a read scope.</b> The ingest path treats every valid key alike, but
 * the read path ({@code GET /audit/logs}) confines each key to the app it is bound to. The
 * binding lives in the checked-in {@link ClientRegistry} ({@code clients.json}); the key itself
 * comes either from a named property (the owner's {@code API_KEY}) or from the aggregated
 * {@link ClientKeyMap} ({@code MONOLITH_CLIENT_KEYS}) so new apps need no new secret. See
 * {@link AuthenticatedClient}.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Paths served without credentials. Kept in sync with SecurityConfig's permitAll matchers. */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/swagger-ui", "/v3/api-docs", "/actuator");
    private static final List<String> PUBLIC_EXACT = List.of("/", "/health", "/error");

    private record KeyBinding(String token, AuthenticatedClient client) {
    }

    private final List<KeyBinding> keyBindings;
    private final String allowedEmail;
    private final GoogleIdTokenVerifier googleVerifier;

    public ApiKeyAuthFilter(Environment environment,
                            ClientRegistry clientRegistry,
                            ClientKeyMap clientKeyMap,
                            @Value("${dashboard.allowed-email:}") String allowedEmail) {
        this.keyBindings = new ArrayList<>();
        for (ClientRegistry.ClientDefinition client : clientRegistry.clients()) {
            String source = hasText(client.keyProperty()) ? client.keyProperty() : "MONOLITH_CLIENT_KEYS[" + client.name() + "]";
            String token = hasText(client.keyProperty())
                    ? environment.getProperty(client.keyProperty())
                    : clientKeyMap.keyFor(client.name()).orElse(null);
            if (token == null || token.isBlank()) {
                log.warn("[Auth] Registered client '{}' has no key set ({}); it cannot authenticate.",
                        client.name(), source);
                continue;
            }
            addKey(token, AuthenticatedClient.fromScope(client.name(), client.readScope()));
        }
        this.allowedEmail = allowedEmail == null ? "" : allowedEmail.trim();

        this.googleVerifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance()).build();

        if (this.keyBindings.isEmpty()) {
            log.error("[Auth] No registered client has a key configured. "
                    + "Authenticated endpoints will reject every request until one is set.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_EXACT.contains(path)
                || PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = bearerToken(request);
        AuthenticatedClient client = token == null ? null : authenticate(token);

        if (client == null) {
            reject(response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        client, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        chain.doFilter(request, response);
    }

    /** @return the client the token authenticates as, or {@code null} if the token is not valid. */
    private AuthenticatedClient authenticate(String token) {
        for (KeyBinding binding : keyBindings) {
            if (constantTimeEquals(binding.token(), token)) {
                return binding.client();
            }
        }
        return verifyGoogleIdToken(token);
    }

    private AuthenticatedClient verifyGoogleIdToken(String token) {
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
            return AuthenticatedClient.crossApp(email);
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

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private void addKey(String token, AuthenticatedClient client) {
        if (token == null || token.isBlank()) {
            return;
        }
        String trimmed = token.trim();
        for (KeyBinding existing : keyBindings) {
            if (existing.token().equals(trimmed)) {
                return; // same string handed to two slots — first registration wins
            }
        }
        keyBindings.add(new KeyBinding(trimmed, client));
    }
}
