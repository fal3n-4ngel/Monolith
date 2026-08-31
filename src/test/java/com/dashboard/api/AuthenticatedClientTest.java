package com.dashboard.api;

import com.dashboard.api.events.AppRef;
import com.dashboard.api.security.AuthenticatedClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedClientTest {

    private static final List<String> KNOWN_APPS = List.of("continuum-home", "monolith-dashboard");

    @Test
    void allAndBlankAndStarMeanCrossAppRead() {
        for (String scope : new String[] {"all", "ALL", "*", "", "  ", null}) {
            AuthenticatedClient client = AuthenticatedClient.fromScope("api-key", scope, KNOWN_APPS);
            assertThat(client.isCrossApp()).as("scope=%s", scope).isTrue();
            assertThat(client.canRead()).isTrue();
            assertThat(client.boundApp()).isEmpty();
        }
    }

    @Test
    void noneMeansAuthenticatedButNoReadAccess() {
        AuthenticatedClient client = AuthenticatedClient.fromScope("continuum", "none", KNOWN_APPS);

        assertThat(client.isCrossApp()).isFalse();
        assertThat(client.canRead()).isFalse();
        assertThat(client.boundApp()).isEmpty();
    }

    @Test
    void aRegisteredAppIdBindsToThatApp() {
        AuthenticatedClient client = AuthenticatedClient.fromScope("continuum", "continuum-home", KNOWN_APPS);

        assertThat(client.isCrossApp()).isFalse();
        assertThat(client.canRead()).isTrue();
        assertThat(client.boundApp()).contains(new AppRef("continuum-home"));
    }

    @Test
    void anUnknownScopeValueFailsFastRatherThanGuessing() {
        assertThatThrownBy(() -> AuthenticatedClient.fromScope("continuum", "not-an-app", KNOWN_APPS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid read scope");
    }

    @Test
    void nameIsExposedAsThePrincipalName() {
        assertThat(AuthenticatedClient.crossApp("api-key").getName()).isEqualTo("api-key");
    }
}
