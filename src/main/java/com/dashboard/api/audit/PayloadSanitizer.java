package com.dashboard.api.audit;

import com.dashboard.api.config.AuditProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounds and scrubs the free-form {@code metadata} / {@code context} maps before they reach Firestore.
 *
 * <p>This is a cost control as much as a safety one. Firestore auto-indexes every scalar field
 * in a document, so an unbounded caller-supplied map means unbounded index entries — billed as
 * storage forever, and paid again in write latency on every insert.
 */
@Component
public class PayloadSanitizer {

    /** Keys whose values are replaced wholesale, matched case-insensitively as substrings. */
    private static final Set<String> REDACTED_KEY_FRAGMENTS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "authorization", "credential", "privatekey", "private_key", "cookie", "session"
    );

    private static final String REDACTED = "[REDACTED]";

    private final AuditProperties props;

    public PayloadSanitizer(AuditProperties props) {
        this.props = props;
    }

    public Map<String, Object> sanitize(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return sanitizeMap(input, 0);
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> input, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        int kept = 0;
        boolean truncated = false;

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if (kept >= props.maxMapEntries()) {
                truncated = true;
                break;
            }
            String key = truncate(entry.getKey(), 128);
            out.put(key, isRedacted(key) ? REDACTED : sanitizeValue(entry.getValue(), depth));
            kept++;
        }
        if (truncated) {
            out.put("_truncatedKeys", input.size() - kept);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value, int depth) {
        return switch (value) {
            case null -> null;
            case String s -> truncate(s, props.maxValueLength());
            case Number n -> n;
            case Boolean b -> b;
            case Map<?, ?> m -> depth + 1 >= props.maxMapDepth()
                    ? REDACTED + "(depth)"
                    : sanitizeMap((Map<String, Object>) m, depth + 1);
            case Iterable<?> it -> sanitizeList(it, depth);
            default -> truncate(value.toString(), props.maxValueLength());
        };
    }

    private List<Object> sanitizeList(Iterable<?> input, int depth) {
        List<Object> out = new ArrayList<>();
        for (Object item : input) {
            if (out.size() >= props.maxMapEntries()) {
                break;
            }
            out.add(sanitizeValue(item, depth + 1));
        }
        return out;
    }

    private static boolean isRedacted(String key) {
        String lower = key.toLowerCase(Locale.ROOT).replace("-", "");
        return REDACTED_KEY_FRAGMENTS.stream().anyMatch(lower::contains);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /**
     * Client IPs are personal data. When {@code audit.hash-client-ip} is on we keep a stable
     * pseudonym instead — still correlatable across events, no longer directly identifying.
     */
    public String prepareClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        if (!props.hashClientIp()) {
            return truncate(clientIp, 64);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(clientIp.trim().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return null; // SHA-256 is mandated by the JDK; unreachable in practice.
        }
    }
}
