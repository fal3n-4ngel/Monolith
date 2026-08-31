package com.dashboard.api;

import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.dto.AuditLogEntry;
import com.dashboard.api.dto.AuditLogPage;
import com.dashboard.api.dto.AuditLogQuery;
import com.dashboard.api.events.AppRef;
import com.dashboard.api.events.AppRegistry;
import com.dashboard.api.query.AuditLogService;
import com.dashboard.api.query.BigQueryAuditLogRepository;
import com.dashboard.api.query.ForbiddenScopeException;
import com.dashboard.api.security.AuthenticatedClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuditLogServiceTest {

    private static final AuditProperties PROPS =
            new AuditProperties(32, 512, 4, 120, 300, false, "events", "US", 50, 200, 30, 30, 100_000_000L);
    private static final AppRegistry APPS =
            new AppRegistry(new DefaultResourceLoader(), new ObjectMapper(), "classpath:apps.json");
    private static final AppRef CONTINUUM_HOME = new AppRef("continuum-home");

    private BigQueryAuditLogRepository repository;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        repository = mock(BigQueryAuditLogRepository.class);
        when(repository.search(any())).thenReturn(List.of());
        service = new AuditLogService(repository, PROPS);
    }

    private BigQueryAuditLogRepository.Criteria capture() {
        ArgumentCaptor<BigQueryAuditLogRepository.Criteria> captor =
                ArgumentCaptor.forClass(BigQueryAuditLogRepository.Criteria.class);
        verify(repository).search(captor.capture());
        return captor.getValue();
    }

    private static AuditLogQuery query(String sourceApp) {
        return AuditLogQuery.parse(APPS, sourceApp, null, null, null, null, null, null);
    }

    @Test
    void scopedCredentialIsConfinedToItsOwnAppEvenWithNoParam() {
        service.query(AuthenticatedClient.boundTo("continuum-home", CONTINUUM_HOME), query(null));

        assertThat(capture().sourceApp()).contains(CONTINUUM_HOME);
    }

    @Test
    void scopedCredentialAskingForAnotherAppIsForbiddenAndNeverHitsBigQuery() {
        assertThatThrownBy(() -> service.query(
                AuthenticatedClient.boundTo("continuum-home", CONTINUUM_HOME),
                query("monolith-dashboard")))
                .isInstanceOf(ForbiddenScopeException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void scopedCredentialMayRedundantlyNameItsOwnApp() {
        service.query(AuthenticatedClient.boundTo("continuum-home", CONTINUUM_HOME), query("continuum-home"));

        assertThat(capture().sourceApp()).contains(CONTINUUM_HOME);
    }

    @Test
    void aCredentialWithNoReadScopeIsForbidden() {
        assertThatThrownBy(() -> service.query(AuthenticatedClient.noRead("continuum-home"), query(null)))
                .isInstanceOf(ForbiddenScopeException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void crossAppCredentialReadsEveryAppByDefault() {
        AuditLogPage page = service.query(AuthenticatedClient.crossApp("api-key"), query(null));

        assertThat(capture().sourceApp()).isEmpty();
        assertThat(page.scope()).isEqualTo("all");
    }

    @Test
    void crossAppCredentialMayNarrowToOneApp() {
        service.query(AuthenticatedClient.crossApp("api-key"), query("continuum-home"));

        assertThat(capture().sourceApp()).contains(CONTINUUM_HOME);
    }

    @Test
    void limitIsClampedToTheConfiguredCeiling() {
        service.query(AuthenticatedClient.crossApp("api-key"),
                AuditLogQuery.parse(APPS, null, null, null, null, null, null, 99_999));

        assertThat(capture().limit()).isEqualTo(200);
    }

    @Test
    void missingFromFallsBackToTheLookbackWindow() {
        Instant lowerBound = Instant.now().minusSeconds(31L * 24 * 3600);
        service.query(AuthenticatedClient.crossApp("api-key"), query(null));
        Instant upperBound = Instant.now().minusSeconds(29L * 24 * 3600);

        assertThat(capture().from()).isBetween(lowerBound, upperBound);
    }

    @Test
    void nextBeforeIsSetOnlyWhenThePageCameBackFull() {
        AuditLogEntry entry = new AuditLogEntry("expenses", "e", "continuum-home", "u",
                "EXPENSE_CREATED", "CREATE", "x", 1L, "2026-08-30T00:00:00Z", "2026-08-30T00:00:01Z", null);
        when(repository.search(any())).thenReturn(Collections.nCopies(50, entry));

        AuditLogPage full = service.query(AuthenticatedClient.crossApp("api-key"),
                AuditLogQuery.parse(APPS, null, null, null, null, null, null, 50));

        assertThat(full.nextBefore()).isEqualTo("2026-08-30T00:00:00Z");

        when(repository.search(any())).thenReturn(Collections.nCopies(3, entry));
        AuditLogPage partial = service.query(AuthenticatedClient.crossApp("api-key"),
                AuditLogQuery.parse(APPS, null, null, null, null, null, null, 50));

        assertThat(partial.nextBefore()).isNull();
    }
}
