package com.dashboard.api;

import com.dashboard.api.events.Action;
import com.dashboard.api.events.AppRegistry;
import com.dashboard.api.events.EventRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppRegistryTest {

    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppRegistry load(String location) {
        return new AppRegistry(LOADER, MAPPER, location);
    }

    @Test
    void loadsTheBundledRegistry() {
        AppRegistry apps = load("classpath:apps.json");

        assertThat(apps.appIds()).contains("continuum-home", "monolith-dashboard", "chayakudikanpooyalo");
        assertThat(apps.resolveApp("CONTINUUM-HOME")).isPresent();
        assertThat(apps.resolveApp("nope")).isEmpty();
    }

    @Test
    void resolvesAnEventToItsDomainAndDerivedActionAndTable() {
        AppRegistry apps = load("classpath:apps.json");

        EventRef created = apps.resolveEvent("continuum-home", "expense_created").orElseThrow();
        assertThat(created.domain()).isEqualTo("expenses");
        assertThat(created.action()).isEqualTo(Action.CREATE);
        assertThat(created.tableFor("continuum-home")).isEqualTo("continuum_home_expenses");

        assertThat(apps.resolveEvent("continuum-home", "WATCHLIST_REMOVED").orElseThrow().action())
                .isEqualTo(Action.DELETE);
        assertThat(apps.resolveEvent("continuum-home", "SALARY_LOGGED").orElseThrow().action())
                .isEqualTo(Action.CREATE);
    }

    @Test
    void anEventIsScopedToItsOwnApp() {
        AppRegistry apps = load("classpath:apps.json");

        assertThat(apps.resolveEvent("continuum-home", "REPORT_RUN")).isEmpty();
        assertThat(apps.resolveEvent("monolith-dashboard", "REPORT_RUN")).isPresent();
    }

    @Test
    void domainsIsTheUnionAcrossApps() {
        assertThat(load("classpath:apps.json").domains())
                .contains("expenses", "watchlist", "investments", "subscriptions", "account", "usage");
    }

    @Test
    void explicitActionOverrideIsHonoured() {
        AppRegistry apps = load("classpath:apps-ok.json");

        assertThat(apps.resolveEvent("task-app", "TASK_SNOOZED").orElseThrow().action()).isEqualTo(Action.UPDATE);
        assertThat(apps.resolveEvent("task-app", "TASK_REMOVED").orElseThrow().action()).isEqualTo(Action.DELETE);
    }

    @Test
    void anEventWithNoRecognisedActionSuffixFailsAtStartup() {
        assertThatThrownBy(() -> load("classpath:apps-bad-event.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no recognised action suffix");
    }

    @Test
    void anEventNameSharedByTwoAppsFailsAtStartup() {
        assertThatThrownBy(() -> load("classpath:apps-duplicate-event.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique across apps");
    }

    @Test
    void aMissingRegistryFileFailsAtStartup() {
        assertThatThrownBy(() -> load("classpath:no-such-apps.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }
}
