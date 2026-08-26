package com.dashboard.api;

import com.dashboard.api.audit.BigQueryAuditWriter;
import com.dashboard.api.config.AuditProperties;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.QueryJobConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BigQueryAuditWriter} is called directly (not through a Spring proxy), so {@code @Async}
 * has no effect here — every call runs synchronously, which is exactly what these tests want.
 */
class BigQueryAuditWriterTest {

    private static final AuditProperties ENABLED_PROPS = props(true);

    private BigQuery bigQuery;
    private BigQueryAuditWriter writer;

    private static AuditProperties props(boolean bigqueryEnabled) {
        return new AuditProperties(
                Set.of("https://continuum-home.vercel.app"), Set.of("continuum-home"),
                32, 512, 4, false,
                Duration.ofDays(90), 50, 200, 120, Duration.ofMinutes(15),
                bigqueryEnabled, "audit", "events", "US");
    }

    private static Map<String, Object> baseDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("logId", "log-1");
        document.put("eventId", "evt-1");
        document.put("sourceApp", "continuum-home");
        document.put("eventType", "USER_SESSION_ACTIVE");
        document.put("severity", "INFO");
        document.put("userId", "uid-42");
        document.put("timestamp", 1_700_000_000_000L);
        document.put("receivedAt", 1_700_000_000_500L);
        return document;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BigQuery> providerReturning(BigQuery client) {
        ObjectProvider<BigQuery> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    @BeforeEach
    void setUp() {
        bigQuery = mock(BigQuery.class);
        InsertAllResponse response = mock(InsertAllResponse.class);
        when(response.hasErrors()).thenReturn(false);
        when(bigQuery.insertAll(any(InsertAllRequest.class))).thenReturn(response);

        writer = new BigQueryAuditWriter(providerReturning(bigQuery), ENABLED_PROPS);
    }

    @Test
    void insertsEventUsingEventIdAsInsertIdForDedup() {
        writer.enqueue(baseDocument());

        ArgumentCaptor<InsertAllRequest> captor = ArgumentCaptor.forClass(InsertAllRequest.class);
        verify(bigQuery).insertAll(captor.capture());

        InsertAllRequest request = captor.getValue();
        assertThat(request.getRows()).hasSize(1);
        assertThat(request.getRows().get(0).getId()).isEqualTo("evt-1");
        assertThat(request.getRows().get(0).getContent())
                .containsEntry("source_app", "continuum-home")
                .containsEntry("event_type", "USER_SESSION_ACTIVE")
                .containsEntry("user_id", "uid-42");
    }

    @Test
    void fallsBackToLogIdWhenEventIdIsAbsent() {
        Map<String, Object> document = baseDocument();
        document.remove("eventId");

        writer.enqueue(document);

        ArgumentCaptor<InsertAllRequest> captor = ArgumentCaptor.forClass(InsertAllRequest.class);
        verify(bigQuery).insertAll(captor.capture());
        assertThat(captor.getValue().getRows().get(0).getId()).isEqualTo("log-1");
    }

    @Test
    void upsertsIdentityWhenMetadataCarriesAVerifiedEmail() throws InterruptedException {
        Map<String, Object> document = baseDocument();
        document.put("metadata", Map.of(
                "email", "  Person@Example.com ", "name", "Person Name", "authProvider", "google"));

        writer.enqueue(document);

        ArgumentCaptor<QueryJobConfiguration> captor = ArgumentCaptor.forClass(QueryJobConfiguration.class);
        verify(bigQuery).query(captor.capture());

        QueryJobConfiguration config = captor.getValue();
        assertThat(config.getQuery()).contains("MERGE");
        assertThat(config.getNamedParameters().get("email").getValue()).isEqualTo("person@example.com");
        assertThat(config.getNamedParameters().get("localUserId").getValue()).isEqualTo("uid-42");
        assertThat(config.getNamedParameters().get("displayName").getValue()).isEqualTo("Person Name");
    }

    @Test
    void neverMatchesOnNameAloneWithoutAnEmail() throws InterruptedException {
        Map<String, Object> document = baseDocument();
        document.put("metadata", Map.of("name", "Person Name"));

        writer.enqueue(document);

        verify(bigQuery, never()).query(any());
    }

    @Test
    void skipsIdentityLinkingForAnonymousUsers() throws InterruptedException {
        Map<String, Object> document = baseDocument();
        document.put("userId", "anonymous");
        document.put("metadata", Map.of("email", "person@example.com"));

        writer.enqueue(document);

        verify(bigQuery, never()).query(any());
    }

    @Test
    void bigqueryDisabledSkipsBothWrites() {
        BigQueryAuditWriter disabledWriter = new BigQueryAuditWriter(providerReturning(bigQuery), props(false));

        disabledWriter.enqueue(baseDocument());

        verifyNoInteractions(bigQuery);
    }
}
