package com.dashboard.api.service;

import com.dashboard.api.config.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pages a Discord channel when a security rule trips.
 *
 * <p>Three properties this needs and the previous version lacked:
 *
 * <ul>
 *   <li><b>Off the request thread.</b> The webhook call ran inline with no connect or read
 *       timeout. A hung Discord held a Cloud Run request slot open indefinitely; an alert
 *       storm could pin all 80 concurrent slots on an instance.</li>
 *   <li><b>Deduplicated.</b> A stolen deployment in a render loop generated one webhook post
 *       per page view — enough to get the webhook rate-limited and effectively disabled,
 *       exactly when it matters.</li>
 *   <li><b>Configured, not hardcoded.</b> The URL is a secret and is now absent by default.</li>
 * </ul>
 */
@Service
public class DiscordAlertService {

    private static final Logger log = LoggerFactory.getLogger(DiscordAlertService.class);
    private static final int COLOR_ALERT_RED = 0xE74C3C;

    /** Guards against unbounded growth if an attacker rotates origins to defeat the cooldown. */
    private static final int MAX_TRACKED_ORIGINS = 512;

    public record OriginAlert(String sourceApp, String origin, String originSource,
                              String clientIp, String eventType, String userAgent, String logId) {}

    private final RestClient restClient;
    private final AuditProperties props;
    private final String webhookUrl;
    private final ConcurrentHashMap<String, Instant> lastAlertByOrigin = new ConcurrentHashMap<>();

    public DiscordAlertService(@Qualifier("webhookRestClient") RestClient restClient,
                               AuditProperties props,
                               @Value("${discord.webhook-url:}") String webhookUrl) {
        this.restClient = restClient;
        this.props = props;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        if (this.webhookUrl.isBlank()) {
            log.info("[DiscordAlert] No webhook configured; security alerts will be logged only.");
        }
    }

    @Async("alertExecutor")
    public void alertUnauthorizedOrigin(OriginAlert alert) {
        if (webhookUrl.isBlank() || !shouldDispatch(alert.origin())) {
            return;
        }
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .body(buildPayload(alert))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[DiscordAlert] Dispatched alert for origin [{}]", alert.origin());
        } catch (Exception e) {
            // Alerting is best-effort: the audit record is already durable regardless.
            log.error("[DiscordAlert] Webhook dispatch failed for origin [{}]: {}",
                    alert.origin(), e.getMessage());
        }
    }

    /** One alert per origin per cooldown window. */
    private boolean shouldDispatch(String origin) {
        String key = origin == null ? "unknown" : origin;
        Instant now = Instant.now();
        Instant cutoff = now.minus(props.alertCooldown());

        if (lastAlertByOrigin.size() > MAX_TRACKED_ORIGINS) {
            lastAlertByOrigin.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
        Instant previous = lastAlertByOrigin.get(key);
        if (previous != null && previous.isAfter(cutoff)) {
            log.debug("[DiscordAlert] Suppressed duplicate alert for [{}]", key);
            return false;
        }
        lastAlertByOrigin.put(key, now);
        return true;
    }

    private Map<String, Object> buildPayload(OriginAlert alert) {
        Map<String, Object> embed = Map.of(
                "title", "Unauthorized deployment origin detected",
                "description", "A postback claiming a known source app arrived from an origin "
                        + "that is not on the authorized list. The codebase may have been cloned and redeployed.",
                "color", COLOR_ALERT_RED,
                "timestamp", Instant.now().toString(),
                "fields", List.of(
                        field("Source App", alert.sourceApp(), true),
                        field("Event Type", alert.eventType(), true),
                        field("Detected Origin", alert.origin(), false),
                        field("Origin Evidence", alert.originSource(), true),
                        field("Client IP", alert.clientIp(), true),
                        field("Audit Log ID", alert.logId(), false),
                        field("User Agent", alert.userAgent(), false)));

        return Map.of(
                "username", "Monolith Security Watchdog",
                "embeds", List.of(embed));
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        String safe = (value == null || value.isBlank()) ? "unknown" : value;
        // Discord rejects the whole payload if any field value exceeds 1024 characters.
        return Map.of("name", name,
                "value", safe.length() > 1024 ? safe.substring(0, 1021) + "..." : safe,
                "inline", inline);
    }
}
