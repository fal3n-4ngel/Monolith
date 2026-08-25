package com.dashboard.api.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts the caller's IP from the proxy chain.
 *
 * <p>On Cloud Run the load balancer appends the real client IP to {@code X-Forwarded-For},
 * so the <i>first</i> entry is the closest thing to the origin client. It is still
 * client-influenced — an attacker can prepend arbitrary values — so treat the result as a
 * best-effort grouping key, never as an authorization input.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first.length() > 64 ? first.substring(0, 64) : first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
