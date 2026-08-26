package com.dashboard.api;

import com.dashboard.api.audit.OriginValidator;
import com.dashboard.api.config.AuditProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OriginValidatorTest {

    private static final OriginValidator VALIDATOR = new OriginValidator(new AuditProperties(
            Set.of("https://continuum-home.vercel.app", "http://localhost:3000"),
            Set.of("continuum-home"),
            32, 512, 4, false,
            Duration.ofDays(90), 50, 200, 120, Duration.ofMinutes(15),
            false, "audit", "US"));

    @Test
    void authorizedOriginHeaderIsNotFlagged() {
        var verdict = VALIDATOR.evaluate(
                "continuum-home", "https://continuum-home.vercel.app", null, Map.of());

        assertThat(verdict.authorized()).isTrue();
        assertThat(verdict.stolenBrand()).isFalse();
        assertThat(verdict.source()).isEqualTo(OriginValidator.Source.ORIGIN_HEADER);
    }

    @Test
    void suffixLookalikeDomainIsFlagged() {
        // The previous prefix match accepted this as authorized.
        var verdict = VALIDATOR.evaluate(
                "continuum-home", "https://continuum-home.vercel.app.attacker.io", null, Map.of());

        assertThat(verdict.authorized()).isFalse();
        assertThat(verdict.stolenBrand()).isTrue();
    }

    @Test
    void localhostPortIsMatchedExactly() {
        assertThat(VALIDATOR.evaluate("continuum-home", "http://localhost:3000", null, Map.of())
                .authorized()).isTrue();
        // A prefix match treated :30000 as :3000.
        assertThat(VALIDATOR.evaluate("continuum-home", "http://localhost:30000", null, Map.of())
                .authorized()).isFalse();
    }

    @Test
    void forgedBodyOriginCannotOverrideTheRealHeader() {
        var verdict = VALIDATOR.evaluate("continuum-home",
                "https://stolen-fork.vercel.app", null,
                Map.of("clientOrigin", "https://continuum-home.vercel.app"));

        assertThat(verdict.origin()).isEqualTo("https://stolen-fork.vercel.app");
        assertThat(verdict.source()).isEqualTo(OriginValidator.Source.ORIGIN_HEADER);
        assertThat(verdict.stolenBrand()).isTrue();
    }

    @Test
    void bodyContextIsUsedOnlyWhenNoHeaderIsPresent() {
        var verdict = VALIDATOR.evaluate("continuum-home", null, null,
                Map.of("clientHref", "https://stolen-fork.vercel.app/dashboard?tab=expenses"));

        assertThat(verdict.origin()).isEqualTo("https://stolen-fork.vercel.app");
        assertThat(verdict.source()).isEqualTo(OriginValidator.Source.CLIENT_BODY);
        assertThat(verdict.stolenBrand()).isTrue();
    }

    @Test
    void refererIsPreferredOverBodyAndReducedToItsOrigin() {
        var verdict = VALIDATOR.evaluate("continuum-home", null,
                "https://continuum-home.vercel.app/some/deep/path", Map.of());

        assertThat(verdict.origin()).isEqualTo("https://continuum-home.vercel.app");
        assertThat(verdict.source()).isEqualTo(OriginValidator.Source.REFERER_HEADER);
        assertThat(verdict.authorized()).isTrue();
    }

    @Test
    void unknownSourceAppFromUnknownOriginIsRecordedButNotPaged() {
        // Someone else's app posting telemetry is not brand theft; log it, do not page.
        var verdict = VALIDATOR.evaluate("some-other-app", "https://elsewhere.example", null, Map.of());

        assertThat(verdict.authorized()).isFalse();
        assertThat(verdict.stolenBrand()).isFalse();
    }

    @Test
    void serverToServerCallWithNoOriginIsNotFlagged() {
        var verdict = VALIDATOR.evaluate("continuum-home", null, null, Map.of());

        assertThat(verdict.origin()).isNull();
        assertThat(verdict.stolenBrand()).isFalse();
    }

    @Test
    void opaqueAndMalformedOriginsAreIgnoredRatherThanCompared() {
        assertThat(OriginValidator.normalize("null")).isNull();
        assertThat(OriginValidator.normalize("   ")).isNull();
        assertThat(OriginValidator.normalize("http://")).isNull();
    }

    @Test
    void defaultPortsAndCasingAreNormalized() {
        assertThat(OriginValidator.normalize("HTTPS://Continuum-Home.Vercel.App:443"))
                .isEqualTo("https://continuum-home.vercel.app");
        assertThat(OriginValidator.normalize("continuum-home.vercel.app"))
                .isEqualTo("https://continuum-home.vercel.app");
    }
}
