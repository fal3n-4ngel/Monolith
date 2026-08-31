package com.dashboard.api.security;

import com.dashboard.api.events.AppRegistry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Registered API credentials with their read scope and report allotment.
 *
 * <p>Two sources: the explicit entries in {@code clients.json} (the owner key, plus any genuinely
 * cross-app consumer), and one credential synthesized per app in {@code apps.json} that declares a
 * {@code readback} block — self-scoped, key derived from the app id. An explicit entry scoped to an
 * app wins over the synthesized one for that app.
 *
 * <p>Holds no secret values. A bad scope, duplicate name, or missing file fails startup.
 */
@Component
public class ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientDefinition(String name, String keyProperty, String readScope, List<String> reports) {
        public ClientDefinition {
            reports = reports == null ? List.of() : List.copyOf(reports);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Registry(List<ClientDefinition> clients) {
    }

    private final List<ClientDefinition> clients;

    public ClientRegistry(ResourceLoader resourceLoader,
                          ObjectMapper objectMapper,
                          AppRegistry appRegistry,
                          @Value("${monolith.clients-file:classpath:clients.json}") String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Client registry not found at '" + location + "'");
        }
        List<ClientDefinition> explicit;
        try (InputStream in = resource.getInputStream()) {
            Registry parsed = objectMapper.readValue(in, Registry.class);
            explicit = parsed.clients() == null ? List.of() : List.copyOf(parsed.clients());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read client registry at '" + location + "'", e);
        }

        this.clients = merge(explicit, appRegistry);
        validate(appRegistry);
        log.info("[Auth] Registered {} client(s): {} explicit + {} synthesized from apps.json",
                clients.size(), explicit.size(), clients.size() - explicit.size());
    }

    public List<ClientDefinition> clients() {
        return clients;
    }

    public List<String> reportsFor(String clientName) {
        for (ClientDefinition client : clients) {
            if (client.name().equals(clientName)) {
                return client.reports();
            }
        }
        return List.of();
    }

    private static List<ClientDefinition> merge(List<ClientDefinition> explicit, AppRegistry appRegistry) {
        Set<String> scopedApps = new HashSet<>();
        for (ClientDefinition client : explicit) {
            if (client.readScope() != null) {
                scopedApps.add(client.readScope().trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        List<ClientDefinition> merged = new ArrayList<>(explicit);
        for (AppRegistry.AppDefinition app : appRegistry.apps()) {
            if (app.hasReadback() && !scopedApps.contains(app.id())) {
                merged.add(new ClientDefinition(app.id(), null, app.id(), app.readbackReports()));
            }
        }
        return List.copyOf(merged);
    }

    private void validate(AppRegistry appRegistry) {
        Set<String> seen = new HashSet<>();
        for (ClientDefinition client : clients) {
            if (client.name() == null || client.name().isBlank()) {
                throw new IllegalStateException("Client registry has an entry with no 'name'");
            }
            if (!seen.add(client.name())) {
                throw new IllegalStateException("Client registry has a duplicate name: '" + client.name() + "'");
            }
            AuthenticatedClient.fromScope(client.name(), client.readScope(), appRegistry.appIds());
        }
    }
}
