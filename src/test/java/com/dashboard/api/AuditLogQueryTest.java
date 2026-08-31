package com.dashboard.api;

import com.dashboard.api.dto.AuditLogQuery;
import com.dashboard.api.events.AppRef;
import com.dashboard.api.events.AppRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogQueryTest {

    private static final AppRegistry APPS =
            new AppRegistry(new DefaultResourceLoader(), new ObjectMapper(), "classpath:apps.json");

    @Test
    void acceptsKnownFiltersAndNormalisesThem() {
        AuditLogQuery q = AuditLogQuery.parse(APPS, "continuum-home", "  uid-1  ", "EXPENSES",
                "expense_created", "2026-01-01T00:00:00Z", null, 25);

        assertThat(q.sourceApp()).contains(new AppRef("continuum-home"));
        assertThat(q.userId()).isEqualTo("uid-1");
        assertThat(q.domain()).isEqualTo("expenses");
        assertThat(q.eventType()).isEqualTo("EXPENSE_CREATED");
        assertThat(q.from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(q.limit()).isEqualTo(25);
    }

    @Test
    void rejectsUnknownSourceApp() {
        assertThatThrownBy(() -> AuditLogQuery.parse(APPS, "no-such-app", null, null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsUnknownDomain() {
        assertThatThrownBy(() -> AuditLogQuery.parse(APPS, null, null, "teleportation", null, null, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsUnknownEventType() {
        assertThatThrownBy(() -> AuditLogQuery.parse(APPS, null, null, null, "EXPENSE_YEETED", null, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsEpochMillisForTimeBounds() {
        long millis = Instant.parse("2026-05-01T00:00:00Z").toEpochMilli();

        AuditLogQuery q = AuditLogQuery.parse(APPS, null, null, null, null, String.valueOf(millis), null, null);

        assertThat(q.from()).isEqualTo(Instant.ofEpochMilli(millis));
    }

    @Test
    void rejectsAnUnparseableTimestamp() {
        assertThatThrownBy(() -> AuditLogQuery.parse(APPS, null, null, null, null, "last-tuesday", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void blankFiltersAreTreatedAsAbsent() {
        AuditLogQuery q = AuditLogQuery.parse(APPS, "  ", "", "  ", "", "  ", "", null);

        assertThat(q.sourceApp()).isEmpty();
        assertThat(q.userId()).isNull();
        assertThat(q.domain()).isNull();
        assertThat(q.eventType()).isNull();
        assertThat(q.from()).isNull();
        assertThat(q.before()).isNull();
    }
}
