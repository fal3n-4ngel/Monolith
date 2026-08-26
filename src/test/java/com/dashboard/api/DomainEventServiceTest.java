package com.dashboard.api;

import com.dashboard.api.audit.PayloadSanitizer;
import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.dto.DomainEventDto;
import com.dashboard.api.dto.DomainEventResponse;
import com.dashboard.api.events.DomainEventWriter;
import com.dashboard.api.service.DomainEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DomainEventServiceTest {

    private static final AuditProperties PROPS = new AuditProperties(
            Set.of("https://continuum-home.vercel.app"), Set.of("continuum-home"),
            32, 512, 4, false,
            Duration.ofDays(90), 50, 200, 120, Duration.ofMinutes(15),
            false, "audit", "events", "US");

    private DomainEventWriter writer;
    private DomainEventService service;

    @BeforeEach
    void setUp() {
        writer = mock(DomainEventWriter.class);
        service = new DomainEventService(writer, new PayloadSanitizer(PROPS));
    }

    private static DomainEventDto event(String eventType) {
        DomainEventDto dto = new DomainEventDto();
        dto.setSourceApp("continuum-home");
        dto.setEventType(eventType);
        dto.setUserId("uid-42");
        return dto;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureRow() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(writer).write(anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    private String captureTable() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(writer).write(captor.capture(), anyString(), any());
        return captor.getValue();
    }

    @Test
    void routesToTheServerResolvedTableAndStampsTheSharedColumns() {
        DomainEventDto dto = event("EXPENSE_CREATED");
        dto.setEntityId("exp_1");
        dto.setPayload(Map.of("amount", 42.5, "category", "food"));

        DomainEventResponse response = service.record(dto);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.table()).isEqualTo("continuum_home_expenses");
        assertThat(captureTable()).isEqualTo("continuum_home_expenses");

        Map<String, Object> row = captureRow();
        // source_app + local_user_id are what every cross-app join keys on.
        assertThat(row).containsEntry("source_app", "continuum-home")
                .containsEntry("local_user_id", "uid-42")
                .containsEntry("event_type", "EXPENSE_CREATED")
                .containsEntry("action", "CREATE")
                .containsEntry("entity_id", "exp_1")
                .containsEntry("item_count", 1);
        assertThat(row).containsKey("occurred_at").containsKey("received_at").containsKey("payload");
    }

    @Test
    void unknownEventTypeIsRejectedAndNothingIsWritten() {
        DomainEventResponse response = service.record(event("EXPENSE_YEETED"));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.error()).isEqualTo("unknown_event_type");
        assertThat(response.table()).isNull();
        verifyNoInteractions(writer);
    }

    @Test
    void batchOperationsCarryTheirRowCountInsteadOfEmittingOneEventPerRow() {
        DomainEventDto dto = event("EXPENSE_CREATED");
        dto.setItemCount(200);

        service.record(dto);

        assertThat(captureRow()).containsEntry("item_count", 200);
    }

    @Test
    void clientEventIdIsUsedAsTheDedupKeyWhenSupplied() {
        DomainEventDto dto = event("WATCHLIST_ADDED");
        dto.setEventId("evt-abc");

        assertThat(service.record(dto).eventId()).isEqualTo("evt-abc");
        verify(writer).write(anyString(), org.mockito.ArgumentMatchers.eq("evt-abc"), any());
    }

    @Test
    void missingEventIdIsGeneratedRatherThanLeftNull() {
        DomainEventResponse response = service.record(event("WATCHLIST_ADDED"));

        assertThat(response.eventId()).isNotBlank();
        verify(writer, never()).write(anyString(), org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void wildlySkewedClientTimestampFallsBackToServerTime() {
        DomainEventDto dto = event("EXPENSE_DELETED");
        dto.setTimestamp(99999999999999L); // year 5138

        long before = System.currentTimeMillis();
        DomainEventResponse response = service.record(dto);

        assertThat(response.timestamp()).isBetween(before, System.currentTimeMillis() + 1000);
    }

    @Test
    void payloadSecretsAreRedactedBeforeLeavingTheRequestThread() {
        DomainEventDto dto = event("EXPENSE_CREATED");
        dto.setPayload(Map.of("amount", 10, "apiKey", "super-secret"));

        service.record(dto);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) captureRow().get("payload");
        assertThat(payload).containsEntry("apiKey", "[REDACTED]").containsEntry("amount", 10);
    }
}
