package com.dashboard.api.security;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Registered API credentials with their read scope and report allotment, from {@code clients.json}.
 * Holds no secret values: {@code keyProperty} names a Spring property, or the key is looked up by
 * {@code name} in the {@code MONOLITH_CLIENT_KEYS} secret. A bad scope, duplicate name, or missing
 * file fails startup.
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
                          @Value("${monolith.clients-file:classpath:clients.json}") String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Client registry not found at '" + location + "'");
        }
        try (InputStream in = resource.getInputStream()) {
            Registry parsed = objectMapper.readValue(in, Registry.class);
            this.clients = parsed.clients() == null ? List.of() : List.copyOf(parsed.clients());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read client registry at '" + location + "'", e);
        }
        validate();
        log.info("[Auth] Loaded {} client(s) from '{}'", clients.size(), location);
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

    private void validate() {
        Set<String> seen = new HashSet<>();
        for (ClientDefinition client : clients) {
            if (client.name() == null || client.name().isBlank()) {
                throw new IllegalStateException("Client registry has an entry with no 'name'");
            }
            if (!seen.add(client.name())) {
                throw new IllegalStateException("Client registry has a duplicate name: '" + client.name() + "'");
            }
            AuthenticatedClient.fromScope(client.name(), client.readScope());
        }
    }
}
