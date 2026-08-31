package com.dashboard.api.events;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single allowlist of registered apps and the domain events each may emit, loaded from
 * {@code apps.json}. Replaces the old {@code SourceApp} / {@code DomainEventType} enums: onboarding
 * a new app is a block in that file, not a code change. A bad id, duplicate event name, or
 * undeclared action fails startup.
 *
 * <p>Event names are unique across the whole catalog, so a read filter can resolve one without
 * knowing the app.
 */
@Component
public class AppRegistry {

    private static final Logger log = LoggerFactory.getLogger(AppRegistry.class);

    private static final Pattern APP_ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9_]+$");
    private static final Pattern EVENT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** Suffix → action, so the common event names carry no explicit action in apps.json. */
    private static final Map<String, Action> ACTION_BY_SUFFIX = Map.ofEntries(
            Map.entry("_CREATED", Action.CREATE), Map.entry("_ADDED", Action.CREATE),
            Map.entry("_LOGGED", Action.CREATE), Map.entry("_STARTED", Action.CREATE),
            Map.entry("_OPENED", Action.CREATE), Map.entry("_RUN", Action.CREATE),
            Map.entry("_QUERY", Action.CREATE), Map.entry("_SIGNIN", Action.CREATE),
            Map.entry("_UPDATED", Action.UPDATE), Map.entry("_CHANGED", Action.UPDATE),
            Map.entry("_RENAMED", Action.UPDATE),
            Map.entry("_DELETED", Action.DELETE), Map.entry("_REMOVED", Action.DELETE),
            Map.entry("_ARCHIVED", Action.DELETE));

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawReadback(List<String> reports) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawApp(String id, RawReadback readback, Map<String, List<String>> events) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawRegistry(List<RawApp> apps) {
    }

    /** A registered app: its id, the events it may emit keyed by domain, and its read-back allotment. */
    public record AppDefinition(String id, Map<String, List<EventRef>> eventsByDomain,
                                boolean hasReadback, List<String> readbackReports) {
    }

    private final List<AppDefinition> apps;
    private final Map<String, AppDefinition> byId;
    private final Map<String, EventRef> eventsByName;

    public AppRegistry(ResourceLoader resourceLoader,
                       ObjectMapper objectMapper,
                       @Value("${monolith.apps-file:classpath:apps.json}") String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("App registry not found at '" + location + "'");
        }
        RawRegistry raw;
        try (InputStream in = resource.getInputStream()) {
            raw = objectMapper.readValue(in, RawRegistry.class);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read app registry at '" + location + "'", e);
        }

        this.apps = build(raw);
        this.byId = new LinkedHashMap<>();
        this.eventsByName = new LinkedHashMap<>();
        for (AppDefinition app : apps) {
            byId.put(app.id(), app);
            for (List<EventRef> events : app.eventsByDomain().values()) {
                for (EventRef event : events) {
                    eventsByName.put(event.name(), event);
                }
            }
        }
        log.info("[Apps] Loaded {} app(s), {} event type(s) from '{}'", apps.size(), eventsByName.size(), location);
    }

    public List<AppDefinition> apps() {
        return apps;
    }

    public List<String> appIds() {
        return apps.stream().map(AppDefinition::id).toList();
    }

    public Optional<AppRef> resolveApp(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        AppDefinition app = byId.get(rawId.trim().toLowerCase(Locale.ROOT));
        return app == null ? Optional.empty() : Optional.of(new AppRef(app.id()));
    }

    /** Resolve an event for a specific app — the ingest path, where both are supplied. */
    public Optional<EventRef> resolveEvent(String appId, String rawEventName) {
        if (appId == null || rawEventName == null) {
            return Optional.empty();
        }
        AppDefinition app = byId.get(appId.trim().toLowerCase(Locale.ROOT));
        if (app == null) {
            return Optional.empty();
        }
        String name = rawEventName.trim().toUpperCase(Locale.ROOT);
        return app.eventsByDomain().values().stream()
                .flatMap(List::stream)
                .filter(e -> e.name().equals(name))
                .findFirst();
    }

    /** Resolve an event by name alone — the read path, where the app is already scoped by credential. */
    public Optional<EventRef> resolveEventAnywhere(String rawEventName) {
        if (rawEventName == null || rawEventName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(eventsByName.get(rawEventName.trim().toUpperCase(Locale.ROOT)));
    }

    /** Every distinct domain name — the allowlist for the {@code ?domain=} read filter. */
    public Set<String> domains() {
        Set<String> all = new LinkedHashSet<>();
        for (AppDefinition app : apps) {
            all.addAll(app.eventsByDomain().keySet());
        }
        return all;
    }

    // ---------------------------------------------------------------------------

    private static List<AppDefinition> build(RawRegistry raw) {
        if (raw == null || raw.apps() == null || raw.apps().isEmpty()) {
            throw new IllegalStateException("App registry has no 'apps'");
        }

        List<AppDefinition> result = new ArrayList<>();
        Set<String> seenApps = new LinkedHashSet<>();
        Map<String, String> eventOwner = new LinkedHashMap<>();

        for (RawApp rawApp : raw.apps()) {
            String id = rawApp.id() == null ? "" : rawApp.id().trim().toLowerCase(Locale.ROOT);
            if (!APP_ID.matcher(id).matches()) {
                throw new IllegalStateException("App id '" + rawApp.id() + "' must match " + APP_ID.pattern());
            }
            if (!seenApps.add(id)) {
                throw new IllegalStateException("Duplicate app id: '" + id + "'");
            }

            Map<String, List<EventRef>> eventsByDomain = new LinkedHashMap<>();
            Map<String, List<String>> rawEvents = rawApp.events() == null ? Map.of() : rawApp.events();
            for (Map.Entry<String, List<String>> entry : rawEvents.entrySet()) {
                String domain = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
                if (!DOMAIN.matcher(domain).matches()) {
                    throw new IllegalStateException("App '" + id + "' domain '" + entry.getKey()
                            + "' must match " + DOMAIN.pattern());
                }
                List<EventRef> events = new ArrayList<>();
                for (String spec : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                    EventRef event = parseEvent(id, domain, spec);
                    String prior = eventOwner.putIfAbsent(event.name(), id);
                    if (prior != null) {
                        throw new IllegalStateException("Event '" + event.name() + "' is declared by both '"
                                + prior + "' and '" + id + "'; event names must be unique across apps");
                    }
                    events.add(event);
                }
                eventsByDomain.put(domain, List.copyOf(events));
            }

            boolean hasReadback = rawApp.readback() != null;
            List<String> reports = hasReadback && rawApp.readback().reports() != null
                    ? List.copyOf(rawApp.readback().reports())
                    : List.of();

            result.add(new AppDefinition(id, Map.copyOf(eventsByDomain), hasReadback, reports));
        }
        return List.copyOf(result);
    }

    private static EventRef parseEvent(String appId, String domain, String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalStateException("App '" + appId + "' domain '" + domain + "' has a blank event");
        }
        String[] parts = spec.trim().split(":", 2);
        String name = parts[0].trim().toUpperCase(Locale.ROOT);
        if (!EVENT_NAME.matcher(name).matches()) {
            throw new IllegalStateException("Event name '" + spec + "' must match " + EVENT_NAME.pattern());
        }

        Action action;
        if (parts.length == 2 && !parts[1].isBlank()) {
            try {
                action = Action.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Event '" + name + "' has unknown action '" + parts[1].trim() + "'");
            }
        } else {
            action = deriveAction(name).orElseThrow(() -> new IllegalStateException(
                    "Event '" + name + "' has no recognised action suffix; add one as '" + name + ":create|update|delete'"));
        }
        return new EventRef(name, domain, action);
    }

    private static Optional<Action> deriveAction(String name) {
        return ACTION_BY_SUFFIX.entrySet().stream()
                .filter(e -> name.endsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
