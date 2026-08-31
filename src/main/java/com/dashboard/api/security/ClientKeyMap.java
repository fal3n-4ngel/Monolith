package com.dashboard.api.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a registered client's API key.
 *
 * <p>Two sources, checked in order:
 * <ol>
 *   <li>An explicit entry in the {@code MONOLITH_CLIENT_KEYS} secret ({@code name -> token}, JSON or
 *       {@code name=token,...} CSV) — used to pin a pre-existing key.</li>
 *   <li>Otherwise, a value derived from the client name and the {@code MONOLITH_KEY_SEED} secret,
 *       so a newly registered app needs no secret change at all.</li>
 * </ol>
 * With neither configured for a name, the client simply cannot authenticate.
 */
@Component
public class ClientKeyMap {

    private static final Logger log = LoggerFactory.getLogger(ClientKeyMap.class);
    private static final String HMAC = "HmacSHA256";
    private static final String KEY_VERSION = "k1";
    private static final String DERIVATION_CONTEXT = "monolith:client-key:v1:";

    private final Map<String, String> keysByName;
    private final byte[] seed;

    public ClientKeyMap(ObjectMapper objectMapper,
                        @Value("${MONOLITH_CLIENT_KEYS:}") String raw,
                        @Value("${MONOLITH_KEY_SEED:}") String seed) {
        this.keysByName = parse(objectMapper, raw);
        this.seed = (seed == null || seed.isBlank()) ? null : seed.trim().getBytes(StandardCharsets.UTF_8);
        log.info("[Auth] Loaded {} explicit client key(s); key derivation {}",
                keysByName.size(), this.seed == null ? "OFF (no MONOLITH_KEY_SEED)" : "ON");
    }

    public Optional<String> keyFor(String clientName) {
        String explicit = keysByName.get(clientName);
        if (explicit != null && !explicit.isBlank()) {
            return Optional.of(explicit.trim());
        }
        return deriveKey(clientName);
    }

    /** The deterministic key for a client name, if a seed is configured. */
    public Optional<String> deriveKey(String clientName) {
        if (seed == null || clientName == null || clientName.isBlank()) {
            return Optional.empty();
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(seed, HMAC));
            byte[] digest = mac.doFinal((DERIVATION_CONTEXT + clientName.trim()).getBytes(StandardCharsets.UTF_8));
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return Optional.of("mono_" + KEY_VERSION + "_" + body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not derive client key", e);
        }
    }

    public boolean canDerive() {
        return seed != null;
    }

    public int size() {
        return keysByName.size();
    }

    private static Map<String, String> parse(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, String> parsed = mapper.readValue(trimmed, new TypeReference<>() {
                });
                return parsed == null ? Map.of() : parsed;
            } catch (Exception e) {
                throw new IllegalStateException("MONOLITH_CLIENT_KEYS looks like JSON but did not parse", e);
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : trimmed.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException(
                        "MONOLITH_CLIENT_KEYS entry '" + pair.trim() + "' is not 'name=token'");
            }
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return out;
    }
}
