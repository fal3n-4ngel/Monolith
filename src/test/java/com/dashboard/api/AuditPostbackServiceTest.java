package com.dashboard.api;

import com.dashboard.api.audit.AuditLogWriter;
import com.dashboard.api.audit.BigQueryAuditWriter;
import com.dashboard.api.audit.OriginValidator;
import com.dashboard.api.audit.PayloadSanitizer;
import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.dto.AuditPostbackDto;
import com.dashboard.api.dto.AuditPostbackResponse;
import com.dashboard.api.service.AuditPostbackService;
import com.dashboard.api.service.AuditPostbackService.RequestContext;
import com.dashboard.api.service.DiscordAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuditPostbackServiceTest {

    private static final AuditProperties PROPS = new AuditProperties(
            Set.of("https://continuum-home.vercel.app"), Set.of("continuum-home"),
            32, 512, 4, false,
            Duration.ofDays(90), 50, 200, 120, Duration.ofMinutes(15),
            false, "audit", "events", "US");

    private AuditLogWriter writer;
    private BigQueryAuditWriter bigQueryWriter;
    private DiscordAlertService alerts;
    private AuditPostbackService service;

    @BeforeEach
    void setUp() {
        writer = mock(AuditLogWriter.class);
        bigQueryWriter = mock(BigQueryAuditWriter.class);
        alerts = mock(DiscordAlertService.class);
        service = new AuditPostbackService(
                writer, bigQueryWriter, new OriginValidator(PROPS), new PayloadSanitizer(PROPS), alerts, PROPS);
    }

    private static AuditPostbackDto event(String sourceApp, String eventType) {
        AuditPostbackDto dto = new AuditPostbackDto();
        dto.setSourceApp(sourceApp);
        dto.setEventType(eventType);
        return dto;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePersistedDocument() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(writer).enqueue(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void authorizedEventIsPersistedWithoutAlerting() {
        AuditPostbackResponse response = service.record(
                event("continuum-home", "USER_SESSION_ACTIVE"),
                new RequestContext("https://continuum-home.vercel.app", null, "Mozilla/5.0", "203.0.113.9"));

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.securityAlert()).isNull();
        verify(alerts, never()).alertUnauthorizedOrigin(any());

        Map<String, Object> document = capturePersistedDocument();
        assertThat(document).containsEntry("severity", "INFO")
                .containsEntry("userId", "anonymous")
                .doesNotContainKey("isUnauthorized");
        assertThat(document).containsKey("expiresAt"); // drives the Firestore TTL policy

        // Same document also fans out to the BigQuery sink, regardless of Firestore outcome.
        verify(bigQueryWriter).enqueue(document);
    }

    @Test
    void clientSuppliedEventIdIsCarriedIntoTheDocument() {
        AuditPostbackDto dto = event("continuum-home", "USER_SESSION_ACTIVE");
        dto.setEventId("evt_abc123");

        service.record(dto, RequestContext.EMPTY);

        assertThat(capturePersistedDocument()).containsEntry("eventId", "evt_abc123");
    }

    @Test
    void missingEventIdIsNotStamped() {
        service.record(event("continuum-home", "USER_SESSION_ACTIVE"), RequestContext.EMPTY);

        assertThat(capturePersistedDocument()).doesNotContainKey("eventId");
    }

    @Test
    void unauthorizedOriginIsFlaggedPersistedAndAlerted() {
        AuditPostbackResponse response = service.record(
                event("continuum-home", "USER_LOGIN"),
                new RequestContext("https://stolen-fork.vercel.app", null, "Mozilla/5.0", "198.51.100.4"));

        assertThat(response.securityAlert()).isEqualTo(AuditPostbackResponse.ALERT_UNAUTHORIZED_ORIGIN);
        verify(alerts).alertUnauthorizedOrigin(any());

        Map<String, Object> document = capturePersistedDocument();
        assertThat(document).containsEntry("isUnauthorized", true)
                .containsEntry("resolvedOrigin", "https://stolen-fork.vercel.app")
                .containsEntry("originSource", "ORIGIN_HEADER");
    }

    @Test
    void serverObservedContextIsKeptApartFromClientClaims() {
        AuditPostbackDto dto = event("continuum-home", "USER_LOGIN");
        dto.setContext(new HashMap<>(Map.of("clientOrigin", "https://continuum-home.vercel.app")));

        service.record(dto, new RequestContext(
                "https://stolen-fork.vercel.app", null, "Mozilla/5.0", "198.51.100.4"));

        Map<String, Object> document = capturePersistedDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> observed = (Map<String, Object>) document.get("observed");
        @SuppressWarnings("unchecked")
        Map<String, Object> claimed = (Map<String, Object>) document.get("context");

        assertThat(observed).containsEntry("origin", "https://stolen-fork.vercel.app");
        assertThat(claimed).containsEntry("clientOrigin", "https://continuum-home.vercel.app");
        // The header, not the body, decided the verdict.
        assertThat(document).containsEntry("isUnauthorized", true);
    }

    @Test
    void wildlySkewedClientTimestampFallsBackToServerTime() {
        AuditPostbackDto dto = event("continuum-home", "USER_LOGIN");
        dto.setTimestamp(99999999999999L); // year 5138

        long before = System.currentTimeMillis();
        AuditPostbackResponse response = service.record(dto, RequestContext.EMPTY);

        assertThat(response.timestamp())
                .isBetween(before, System.currentTimeMillis() + 1000);
    }

    @Test
    void plausibleClientTimestampIsHonoured() {
        long clientTime = System.currentTimeMillis() - 5_000;
        AuditPostbackDto dto = event("continuum-home", "USER_LOGIN");
        dto.setTimestamp(clientTime);

        assertThat(service.record(dto, RequestContext.EMPTY).timestamp()).isEqualTo(clientTime);
    }

    @Test
    void unrecognizedSeverityDegradesToInfoInsteadOfFailing() {
        AuditPostbackDto dto = event("continuum-home", "USER_LOGIN");
        dto.setSeverity("catastrophic");

        service.record(dto, RequestContext.EMPTY);

        assertThat(capturePersistedDocument()).containsEntry("severity", "INFO");
    }

    @Test
    void queryLimitIsClampedToTheConfiguredCeiling() {
        service.queryAuditLogs(null, null, null, 100_000);

        verify(writer).query(null, null, null, PROPS.maxQueryLimit());
    }
}
