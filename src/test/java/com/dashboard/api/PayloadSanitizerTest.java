package com.dashboard.api;

import com.dashboard.api.audit.PayloadSanitizer;
import com.dashboard.api.config.AuditProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadSanitizerTest {

    private static AuditProperties props(boolean hashIp) {
        return new AuditProperties(
                Set.of("https://continuum-home.vercel.app"), Set.of("continuum-home"),
                3, 10, 2, hashIp,
                Duration.ofDays(90), 50, 200, 120, Duration.ofMinutes(15));
    }

    private static final PayloadSanitizer SANITIZER = new PayloadSanitizer(props(false));

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

    @Test
    void clientIpIsPassedThroughUnlessHashingIsEnabled() {
        assertThat(SANITIZER.prepareClientIp("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(SANITIZER.prepareClientIp(null)).isNull();
    }

    @Test
    void hashedClientIpIsStableAndNoLongerIdentifying() {
        PayloadSanitizer hashing = new PayloadSanitizer(props(true));

        String first = hashing.prepareClientIp("203.0.113.9");
        String second = hashing.prepareClientIp("203.0.113.9");

        assertThat(first).isEqualTo(second).startsWith("sha256:").doesNotContain("203.0.113.9");
        assertThat(hashing.prepareClientIp("203.0.113.10")).isNotEqualTo(first);
    }
}
