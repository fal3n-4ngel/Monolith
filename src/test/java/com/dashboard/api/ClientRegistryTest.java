package com.dashboard.api;

import com.dashboard.api.events.AppRegistry;
import com.dashboard.api.security.ClientRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientRegistryTest {

    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AppRegistry APPS = new AppRegistry(LOADER, MAPPER, "classpath:apps.json");

    private ClientRegistry load(String location) {
        return new ClientRegistry(LOADER, MAPPER, APPS, location);
    }

    @Test
    void loadsTheBundledRegistryWithItsScopes() {
        ClientRegistry registry = load("classpath:clients.json");

        assertThat(registry.clients())
                .extracting(ClientRegistry.ClientDefinition::name,
                        ClientRegistry.ClientDefinition::readScope)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("owner", "all"),
                        org.assertj.core.groups.Tuple.tuple("continuum", "continuum-home"));
    }

    @Test
    void onlyTheOwnerNamesADedicatedProperty_othersComeFromTheAggregatedSecretOrDerivation() {
        var clients = load("classpath:clients.json").clients();

        var owner = clients.stream().filter(c -> c.name().equals("owner")).findFirst().orElseThrow();
        assertThat(owner.keyProperty()).isEqualTo("dashboard.api-key");

        var continuum = clients.stream().filter(c -> c.name().equals("continuum")).findFirst().orElseThrow();
        assertThat(continuum.keyProperty()).isNull();
    }

    @Test
    void anUnknownReadScopeFailsAtStartup() {
        assertThatThrownBy(() -> load("classpath:clients-bad-scope.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid read scope");
    }

    @Test
    void duplicateClientNamesFailAtStartup() {
        assertThatThrownBy(() -> load("classpath:clients-duplicate.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate name");
    }

    @Test
    void aMissingRegistryFileFailsAtStartup() {
        assertThatThrownBy(() -> load("classpath:no-such-registry.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }
}
