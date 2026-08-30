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
 * The checked-in list of API credentials and the app each may read — loaded from
 * {@code clients.json} on the classpath (override with {@code monolith.clients-file}).
 *
 * <p>Deliberately holds <b>no secret values</b>. Each entry has a {@code readScope} of
 * {@code all}, {@code none}, or a registered {@code sourceApp} id, and its key is resolved at
 * startup as follows:
 * <ul>
 *   <li>if {@code keyProperty} is set — from that Spring property (e.g. the owner's
 *       {@code dashboard.api-key} &larr; {@code API_KEY});</li>
 *   <li>otherwise — from the aggregated {@code MONOLITH_CLIENT_KEYS} secret, keyed by
 *       {@code name} (see {@link ClientKeyMap}).</li>
 * </ul>
 * A bad scope, a duplicate name, or a missing file fails the server at startup rather than
 * silently mis-scoping a caller.
 *
 * <p>Onboarding a new client is one entry here plus its token in {@code MONOLITH_CLIENT_KEYS} —
 * no new Secret Manager secret, no code change.
 */
@Component
public class ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);

    /**
     * One registry row. {@code keyProperty}, when present, names a Spring property (never a key
     * value); when absent, the key comes from {@code MONOLITH_CLIENT_KEYS} under {@code name}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientDefinition(String name, String keyProperty, String readScope) {
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
        log.info("[Auth] Loaded {} registered client(s) from '{}'", clients.size(), location);
    }

    public List<ClientDefinition> clients() {
        return clients;
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
            // Throws IllegalStateException on an unrecognized scope — fail fast at startup.
            AuthenticatedClient.fromScope(client.name(), client.readScope());
        }
    }
}
