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
 *
 * <p>Retries once on a connection failure — the same {@code HttpConnectTimeoutException} seen
 * from {@code BigQueryInserts} on a Cloud Run instance's first outbound call to a host it
 * hasn't talked to yet. Safe to retry unconditionally: Discord accepts a duplicate webhook post
 * as a second message, not an error, and a lost "heartbeat" duplicate is harmless either way.
 */
@Component
public class DiscordNotifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 2;
    private static final int COLOR_GREEN = 0x22C55E;

    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public DiscordNotifier(@Value("${discord.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        if (this.webhookUrl.isBlank()) {
            log.info("[Discord] No webhook configured; event notifications are skipped.");
        }
    }

    /**
     * Fire-and-forget: a Discord hiccup must never affect ingest. Runs on its own executor,
     * not {@code bigqueryExecutor} — a slow BigQuery retry must never delay or starve this.
     */
    @Async("discordExecutor")
    public void notifyDomainEvent(String eventType, String sourceApp, String table, String environment, String eventId) {
        if (webhookUrl.isBlank()) {
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
                } else {
                    log.warn("[Discord] Webhook returned {} for {}", response.statusCode(), eventType);
                }
                return;
            } catch (Exception e) {
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("[Discord] Send failed for {} ({}); retrying", eventType, e.getMessage());
                } else {
                    log.warn("[Discord] Notification failed for {} after {} attempts: {}", eventType, MAX_ATTEMPTS, e.getMessage());
                }
            }
        }
    }

    private HttpRequest buildRequest(String eventType, String sourceApp, String table, String environment, String eventId)
            throws Exception {
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
