package com.dashboard.api;

import com.dashboard.api.events.DomainEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventTypeTest {

    @Test
    void routesEachDomainToItsOwnPerAppTable() {
        assertThat(DomainEventType.EXPENSE_CREATED.tableFor("continuum-home"))
                .isEqualTo("continuum_home_expenses");
        assertThat(DomainEventType.WATCHLIST_REMOVED.tableFor("continuum-home"))
                .isEqualTo("continuum_home_watchlist");
        assertThat(DomainEventType.INVESTMENT_UPDATED.tableFor("continuum-home"))
                .isEqualTo("continuum_home_investments");
        assertThat(DomainEventType.SUBSCRIPTION_DELETED.tableFor("continuum-home"))
                .isEqualTo("continuum_home_subscriptions");
    }

    @Test
    void aSecondAppGetsItsOwnTablesForTheSameDomain() {
        assertThat(DomainEventType.EXPENSE_CREATED.tableFor("budget-cli"))
                .isEqualTo("budget_cli_expenses");
        assertThat(DomainEventType.EXPENSE_CREATED.tableFor("continuum-home"))
                .isNotEqualTo(DomainEventType.EXPENSE_CREATED.tableFor("budget-cli"));
    }

    @Test
    void sourceAppIsNormalizedIntoALegalTableIdentifier() {
        // The DTO bounds sourceApp to [A-Za-z0-9._-]; none of those survive into a table name
        // except as underscores.
        assertThat(DomainEventType.EXPENSE_CREATED.tableFor("My.App-v2"))
                .isEqualTo("my_app_v2_expenses");
    }

    @Test
    void everyEventCarriesTheActionImpliedByItsName() {
        assertThat(DomainEventType.EXPENSE_CREATED.action()).isEqualTo(DomainEventType.Action.CREATE);
        assertThat(DomainEventType.WATCHLIST_ADDED.action()).isEqualTo(DomainEventType.Action.CREATE);
        assertThat(DomainEventType.EXPENSE_UPDATED.action()).isEqualTo(DomainEventType.Action.UPDATE);
        assertThat(DomainEventType.WATCHLIST_REMOVED.action()).isEqualTo(DomainEventType.Action.DELETE);
        assertThat(DomainEventType.SUBSCRIPTION_DELETED.action()).isEqualTo(DomainEventType.Action.DELETE);
    }

    @Test
    void parseAcceptsKnownNamesCaseInsensitivelyAndRejectsEverythingElse() {
        assertThat(DomainEventType.parse("EXPENSE_CREATED")).contains(DomainEventType.EXPENSE_CREATED);
        assertThat(DomainEventType.parse("  expense_created  ")).contains(DomainEventType.EXPENSE_CREATED);

        // The allowlist is the point: an unknown name must not become a new table.
        assertThat(DomainEventType.parse("EXPENSE_YEETED")).isEmpty();
        assertThat(DomainEventType.parse("")).isEmpty();
        assertThat(DomainEventType.parse(null)).isEmpty();
    }
}
