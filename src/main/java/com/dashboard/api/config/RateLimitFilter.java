package com.dashboard.api.config;

import com.dashboard.api.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layered, in-memory admission control:
 *
 * <ol>
 *   <li>A generous per-IP budget on every request — basic flood resilience for the whole
 *       service (health, swagger, actuator), not just ingest.</li>
 *   <li>A tighter per-IP budget on {@code /postback} specifically.</li>
 *   <li>A per-credential budget on {@code /postback}, independent of source IP — a leaked key
 *       replayed from rotating IPs still hits this ceiling, which per-IP limiting alone would
 *       miss.</li>
 *   <li>A tighter per-IP <i>and</i> per-credential budget on the read endpoints ({@code GET
 *       /audit/logs}, {@code /reports}): each runs a BigQuery job, so it deserves a lower
 *       ceiling than an ingest.</li>
 * </ol>
 *
 * <p>Enforcement is per Cloud Run instance and in-memory, so the effective global ceiling is
 * roughly {@code limit × max-instances}. That is the correct trade at this scale: a shared
 * counter would mean a Redis dependency and a network hop on every request. For a hard global
 * limit, or protection against volumetric floods below the application layer, put Cloud Armor
 * in front of the service instead — Cloud Run's own front end already absorbs baseline L3/L4
 * traffic for free regardless of anything configured here.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_BUCKETS = 20_000;

    private final int globalLimitPerMinute;
    private final int postbackLimitPerMinute;
    private final int readLimitPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int globalLimitPerMinute, int postbackLimitPerMinute, int readLimitPerMinute) {
        this.globalLimitPerMinute = globalLimitPerMinute;
        this.postbackLimitPerMinute = postbackLimitPerMinute;
        this.readLimitPerMinute = readLimitPerMinute;
    }

    private static final class Window {
        final AtomicInteger count = new AtomicInteger();
        volatile long startedAt = System.currentTimeMillis();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return globalLimitPerMinute <= 0 && postbackLimitPerMinute <= 0 && readLimitPerMinute <= 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String ip = ClientIpResolver.resolve(request);
        String uri = request.getRequestURI();
        boolean isPostback = uri.endsWith("/postback");
        // Both cost a BigQuery job: the audit read, and running a report.
        boolean isReadQuery = ("GET".equalsIgnoreCase(request.getMethod()) && uri.contains("/audit/logs"))
                || uri.contains("/reports");

        if (globalLimitPerMinute > 0 && !isWithinBudget("ip:" + ip, globalLimitPerMinute)) {
            log.warn("[RateLimit] Rejected {} from [{}] over global {}/min", uri, ip, globalLimitPerMinute);
            reject(response, "Request rate limit exceeded. Retry after 60s.");
            return;
        }

        if (isReadQuery && readLimitPerMinute > 0) {
            if (!isWithinBudget("query-ip:" + ip, readLimitPerMinute)) {
                log.warn("[RateLimit] Rejected read query {} from [{}] over {}/min", uri, ip, readLimitPerMinute);
                reject(response, "Query rate limit exceeded. Retry after 60s.");
                return;
            }

            String credentialBucket = "query-key:" + fingerprint(request.getHeader("Authorization"));
            if (!isWithinBudget(credentialBucket, readLimitPerMinute)) {
                log.warn("[RateLimit] Rejected read query for credential [{}] over {}/min", credentialBucket, readLimitPerMinute);
                reject(response, "Query rate limit exceeded for this credential. Retry after 60s.");
                return;
            }
        }

        if (isPostback && postbackLimitPerMinute > 0) {
            if (!isWithinBudget("postback-ip:" + ip, postbackLimitPerMinute)) {
                log.warn("[RateLimit] Rejected postback from [{}] over {}/min", ip, postbackLimitPerMinute);
                reject(response, "Postback rate limit exceeded. Retry after 60s.");
                return;
            }

            String credentialBucket = "postback-key:" + fingerprint(request.getHeader("Authorization"));
            if (!isWithinBudget(credentialBucket, postbackLimitPerMinute)) {
                log.warn("[RateLimit] Rejected postback for credential [{}] over {}/min", credentialBucket, postbackLimitPerMinute);
                reject(response, "Postback rate limit exceeded for this credential. Retry after 60s.");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        response.getWriter().write(
                "{\"status\":\"REJECTED\",\"error\":\"rate_limited\",\"message\":\"" + message + "\"}");
    }

    private boolean isWithinBudget(String bucketKey, int limit) {
        long now = System.currentTimeMillis();

        // Bound memory: an attacker rotating IPs or credentials must not grow the map without limit.
        if (windows.size() > MAX_TRACKED_BUCKETS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > WINDOW_MS);
        }

        Window window = windows.computeIfAbsent(bucketKey, key -> new Window());
        synchronized (window) {
            if (now - window.startedAt > WINDOW_MS) {
                window.startedAt = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= limit;
        }
    }

    /** Buckets by credential without holding the raw bearer token in memory as a map key. */
    private static String fingerprint(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return "anonymous";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(authorizationHeader.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "anonymous";
        }
    }
}
