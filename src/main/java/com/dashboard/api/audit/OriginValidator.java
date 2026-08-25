package com.dashboard.api.audit;

import com.dashboard.api.config.AuditProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the true origin of a postback and decides whether it is authorized.
 *
 * <p>Two rules matter here, and the previous implementation broke both:
 *
 * <ol>
 *   <li><b>Server-observed headers outrank the request body.</b> A thief controls every
 *       byte of the JSON payload, so {@code context.clientOrigin} is a hint, never evidence.
 *       The {@code Origin} header is set by the browser and cannot be forged by page script.</li>
 *   <li><b>Origins match on host, not prefix.</b> {@code startsWith} let
 *       {@code https://continuum-home.vercel.app.attacker.io} pass as authorized.</li>
 * </ol>
 */
@Component
public class OriginValidator {

    /** Where the origin we judged actually came from, ordered most to least trustworthy. */
    public enum Source { ORIGIN_HEADER, REFERER_HEADER, CLIENT_BODY, NONE }

    public record Verdict(String origin, Source source, boolean authorized, boolean stolenBrand) {
        public static final Verdict ABSENT = new Verdict(null, Source.NONE, true, false);
    }

    private final AuditProperties props;
    private final Set<String> normalizedAllowList;

    public OriginValidator(AuditProperties props) {
        this.props = props;
        this.normalizedAllowList = new LinkedHashSet<>();
        for (String allowed : props.authorizedOrigins()) {
            String normalized = normalize(allowed);
            if (normalized != null) {
                this.normalizedAllowList.add(normalized);
            }
        }
    }

    /**
     * @param sourceApp the app the caller claims to be
     * @param originHeader the {@code Origin} request header, browser-set
     * @param refererHeader the {@code Referer} request header
     * @param context the caller-supplied context map (untrusted)
     */
    public Verdict evaluate(String sourceApp, String originHeader, String refererHeader, Map<String, Object> context) {
        String candidate = normalize(originHeader);
        Source source = Source.ORIGIN_HEADER;

        if (candidate == null) {
            candidate = normalize(refererHeader);
            source = Source.REFERER_HEADER;
        }
        if (candidate == null && context != null) {
            candidate = normalize(stringOf(context.get("clientOrigin")));
            if (candidate == null) {
                candidate = normalize(stringOf(context.get("clientHref")));
            }
            source = Source.CLIENT_BODY;
        }

        // No origin at all: a server-to-server or CLI caller, not a browser. Nothing to judge.
        if (candidate == null) {
            return Verdict.ABSENT;
        }

        boolean authorized = normalizedAllowList.contains(candidate);

        // A fork that renames sourceApp still gets logged, but only a claim on one of *our*
        // app identities from an unknown origin is a brand theft signal worth paging about.
        boolean claimsOurBrand = sourceApp != null
                && props.knownSourceApps().stream().anyMatch(sourceApp::equalsIgnoreCase);

        return new Verdict(candidate, source, authorized, !authorized && claimsOurBrand);
    }

    /**
     * Reduces any URL, origin, or bare host to a canonical {@code scheme://host[:port]}.
     * Returns {@code null} for anything unparseable, so callers never compare junk.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        // Browsers send the literal string "null" for opaque origins (sandboxed iframes, file://).
        if ("null".equalsIgnoreCase(value) || "undefined".equalsIgnoreCase(value)) {
            return null;
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || scheme == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            scheme = scheme.toLowerCase(Locale.ROOT);

            int port = uri.getPort();
            boolean defaultPort = port == -1
                    || ("https".equals(scheme) && port == 443)
                    || ("http".equals(scheme) && port == 80);

            return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
    }
}
