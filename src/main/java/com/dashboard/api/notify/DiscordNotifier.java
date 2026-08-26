package com.dashboard.api.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts event notification embeds to Discord as a deployment heartbeat.
 * Operates asynchronously with a 60-second rate-limit backoff circuit breaker.
 */
@Component
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final int MAX_ATTEMPTS = 2;
    private static final int COLOR_GREEN = 0x22C55E;
    private static final int COLOR_AMBER = 0xF59E0B;
    private static final int COLOR_MUTED_GRAY = 0x6B7280;

    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public DiscordNotifier(@Value("${discord.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        if (this.webhookUrl.isBlank()) {
            log.info("[Discord] No webhook configured; event notifications are skipped.");
        }
    }

    private volatile long mutedUntilMs = 0;

    /** Fire-and-forget event notification executed on a dedicated thread pool. */
    @Async("discordExecutor")
    public void notifyDomainEvent(String eventType, String sourceApp, String table, String environment, String eventId) {
        if (webhookUrl.isBlank()) {
            return;
        }

        if (System.currentTimeMillis() < mutedUntilMs) {
            log.debug("[Discord] Notification skipped for {} — rate-limit circuit breaker active.", eventType);
            return;
        }

        HttpRequest request;
        try {
            request = buildRequest(eventType, sourceApp, table, environment, eventId);
        } catch (Exception e) {
            log.warn("[Discord] Could not build notification", e);
            return;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("[Discord] Notified {} (attempt {})", eventType, attempt);
                    return;
                } else if (response.statusCode() == 429) {
                    // Discord rate-limited: mute for 60 seconds to avoid wasting connection slots
                    mutedUntilMs = System.currentTimeMillis() + 60_000;
                    log.warn("[Discord] Webhook rate-limited (429) for {}; muting for 60s", eventType);
                    return;
                } else {
                    log.warn("[Discord] Webhook returned {} for {}", response.statusCode(), eventType);
                    return;
                }
            } catch (Exception e) {
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("[Discord] Send failed for {} ({}); retrying", eventType, e.getMessage());
                }
            }
        }
    }

    private HttpRequest buildRequest(String eventType, String sourceApp, String table, String environment, String eventId)
            throws Exception {
        String envClean = environment == null ? "unknown" : environment.trim().toLowerCase();

        int color;
        String title;
        String statusText;
        String username;
        String description = null;

        if ("production".equalsIgnoreCase(envClean) || "prod".equalsIgnoreCase(envClean)) {
            color = COLOR_GREEN;
            title = "📥 " + eventType;
            statusText = "PRODUCTION";
            username = "Monolith Events";
        } else if ("uat".equalsIgnoreCase(envClean)) {
            color = COLOR_AMBER;
            title = "🚀 [UAT] " + eventType;
            statusText = "UAT";
            username = "Monolith Events [UAT]";
            description = "🚀 **UAT ENVIRONMENT** — Event emitted from UAT staging.";
        } else {
            color = COLOR_MUTED_GRAY;
            title = "🧪 [TEST RUN] " + eventType;
            statusText = "TEST RUN";
            username = "Monolith Events [TEST RUN]";
            description = "⚠️ **TEST RUN** — Event emitted from a local or test environment.";
        }

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", title);
        embed.put("color", color);
        if (description != null) {
            embed.put("description", description);
        }
        embed.put("timestamp", Instant.now().toString());
        embed.put("fields", List.of(
                field("Source", sourceApp, true),
                field("Environment", environment == null ? "test/local" : environment, true),
                field("Status", statusText, true),
                field("Table", table, true),
                field("Event ID", eventId, false)));

        String body = MAPPER.writeValueAsString(Map.of(
                "username", username,
                "embeds", List.of(embed)));

        return HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        return Map.of("name", name, "value", value == null || value.isBlank() ? "—" : value, "inline", inline);
    }
}
