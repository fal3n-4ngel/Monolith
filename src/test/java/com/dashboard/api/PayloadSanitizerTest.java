package com.dashboard.api;

import com.dashboard.api.ingest.PayloadSanitizer;
import com.dashboard.api.config.AuditProperties;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadSanitizerTest {

    private static final AuditProperties PROPS =
            new AuditProperties(3, 10, 2, 120, 300, false, "events", "US", 50, 200, 30, 30, 100_000_000L);
    private static final PayloadSanitizer SANITIZER = new PayloadSanitizer(PROPS);

    @Test
    void capsEntryCountAndRecordsWhatWasDropped() {
        Map<String, Object> input = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            input.put("key" + i, i);
        }

        Map<String, Object> result = SANITIZER.sanitize(input);

        assertThat(result).hasSize(4); // 3 retained entries + the _truncatedKeys marker
        assertThat(result.get("_truncatedKeys")).isEqualTo(7);
    }

    @Test
    void truncatesOversizedStringValues() {
        Map<String, Object> result = SANITIZER.sanitize(Map.of("note", "x".repeat(100)));

        assertThat((String) result.get("note")).hasSize(11).endsWith("…");
    }

    @Test
    void redactsCredentialBearingKeys() {
        Map<String, Object> result = SANITIZER.sanitize(new HashMap<>(Map.of(
                "apiKey", "sk-live-abcdef",
                "Authorization", "Bearer abc")));

        assertThat(result.values()).containsOnly("[REDACTED]");
    }

    @Test
    void stopsRecursingPastTheDepthLimit() {
        Map<String, Object> deep = Map.of("a", Map.of("b", Map.of("c", Map.of("d", "too-deep"))));

        Map<String, Object> result = SANITIZER.sanitize(deep);

        assertThat(result.toString()).contains("[REDACTED](depth)").doesNotContain("too-deep");
    }

    @Test
    void nullAndEmptyInputCollapseToAnEmptyMap() {
        assertThat(SANITIZER.sanitize(null)).isEmpty();
        assertThat(SANITIZER.sanitize(Map.of())).isEmpty();
    }
}
