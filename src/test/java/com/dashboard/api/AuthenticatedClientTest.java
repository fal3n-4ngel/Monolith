package com.dashboard.api;

import com.dashboard.api.events.SourceApp;
import com.dashboard.api.security.AuthenticatedClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedClientTest {

    @Test
    void allAndBlankAndStarMeanCrossAppRead() {
        for (String scope : new String[] {"all", "ALL", "*", "", "  ", null}) {
            AuthenticatedClient client = AuthenticatedClient.fromScope("api-key", scope);
            assertThat(client.isCrossApp()).as("scope=%s", scope).isTrue();
            assertThat(client.canRead()).isTrue();
            assertThat(client.boundApp()).isEmpty();
        }
    }

    @Test
    void noneMeansAuthenticatedButNoReadAccess() {
        AuthenticatedClient client = AuthenticatedClient.fromScope("continuum", "none");

        assertThat(client.isCrossApp()).isFalse();
        assertThat(client.canRead()).isFalse();
        assertThat(client.boundApp()).isEmpty();
    }

    @Test
    void aRegisteredSourceAppIdBindsToThatApp() {
        AuthenticatedClient client = AuthenticatedClient.fromScope("continuum", "continuum-home");

        assertThat(client.isCrossApp()).isFalse();
        assertThat(client.canRead()).isTrue();
        assertThat(client.boundApp()).contains(SourceApp.CONTINUUM_HOME);
    }

    @Test
    void anUnknownScopeValueFailsFastRatherThanGuessing() {
        assertThatThrownBy(() -> AuthenticatedClient.fromScope("continuum", "not-an-app"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid read scope");
    }

    @Test
    void nameIsExposedAsThePrincipalName() {
        assertThat(AuthenticatedClient.crossApp("api-key").getName()).isEqualTo("api-key");
    }
}
