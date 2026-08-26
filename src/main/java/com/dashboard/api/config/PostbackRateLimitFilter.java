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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP admission control on the ingest endpoint. Without a ceiling, a caller with a valid key
 * (or a leaked one) could convert a loop into an unbounded BigQuery insert bill. Cost control
 * first, abuse control second.
 *
 * <p>Enforcement is per Cloud Run instance and in-memory, so the effective global ceiling is
 * roughly {@code limit × max-instances}. That is the correct trade at this scale: a shared
 * counter would mean a Redis dependency and a network hop on every ingest. For a hard global
 * limit, put Cloud Armor in front of the service instead.
 */
public class PostbackRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PostbackRateLimitFilter.class);
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int limitPerMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public PostbackRateLimitFilter(int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    private static final class Window {
        final AtomicInteger count = new AtomicInteger();
        volatile long startedAt = System.currentTimeMillis();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limitPerMinute <= 0 || !request.getRequestURI().endsWith("/postback");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String client = ClientIpResolver.resolve(request);
        if (isWithinBudget(client)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("[RateLimit] Rejected postback from [{}] over {}/min", client, limitPerMinute);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        response.getWriter().write(
                "{\"status\":\"REJECTED\",\"error\":\"rate_limited\","
                        + "\"message\":\"Postback rate limit exceeded. Retry after 60s.\"}");
    }

    private boolean isWithinBudget(String client) {
        long now = System.currentTimeMillis();

        // Bound memory: an attacker rotating source IPs must not grow the map without limit.
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > WINDOW_MS);
        }

        Window window = windows.computeIfAbsent(client, key -> new Window());
        synchronized (window) {
            if (now - window.startedAt > WINDOW_MS) {
                window.startedAt = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= limitPerMinute;
        }
    }
}
