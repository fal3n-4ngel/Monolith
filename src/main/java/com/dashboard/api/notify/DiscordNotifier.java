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
import java.util.List;
import java.util.Map;

/**
 * Posts a one-line "an event landed" message to Discord — purely a visible heartbeat so a
 * deploy can be eyeballed as working in UAT/prod without a BigQuery query. Not an alert
 * pipeline: no severity, no dedup, no rate-limit backoff. If it ever gets noisy, the fix is to
 * stop calling it from the noisy call site, not to add throttling here.
 */
@Component
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final int COLOR_GREEN = 0x22C55E;

    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public DiscordNotifier(@Value("${discord.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        if (this.webhookUrl.isBlank()) {
            log.info("[Discord] No webhook configured; event notifications are skipped.");
        }
    }

    /** Fire-and-forget: a Discord hiccup must never affect ingest. */
    @Async("bigqueryExecutor")
    public void notifyDomainEvent(String eventType, String sourceApp, String table, String environment, String eventId) {
        if (webhookUrl.isBlank()) {
            return;
        }
        try {
            Map<String, Object> embed = Map.of(
                    "title", "📥 " + eventType,
                    "color", COLOR_GREEN,
                    "timestamp", Instant.now().toString(),
                    "fields", List.of(
                            field("Source", sourceApp, true),
                            field("Environment", environment == null ? "unknown" : environment, true),
                            field("Table", table, true),
                            field("Event ID", eventId, false)));

            String body = MAPPER.writeValueAsString(Map.of(
                    "username", "Monolith Events",
                    "embeds", List.of(embed)));

            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(e -> {
                        log.warn("[Discord] Notification failed: {}", e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("[Discord] Could not build notification", e);
        }
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        return Map.of("name", name, "value", value == null || value.isBlank() ? "—" : value, "inline", inline);
    }
}
