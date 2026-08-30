package com.dashboard.api.query;

import com.dashboard.api.config.AuditProperties;
import com.dashboard.api.dto.AuditLogEntry;
import com.dashboard.api.dto.AuditLogPage;
import com.dashboard.api.dto.AuditLogQuery;
import com.dashboard.api.events.SourceApp;
import com.dashboard.api.security.AuthenticatedClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read path for domain events. Beyond running the query, its job is the security boundary: the
 * {@code source_app} a caller's results are confined to comes from the authenticated credential,
 * never from a request parameter.
 *
 * <ul>
 *   <li>A credential <b>bound to an app</b> can only ever read that app. Passing {@code ?sourceApp=}
 *       for a different app is a 403, not a silently ignored hint.</li>
 *   <li>A <b>cross-app</b> credential reads every app by default, or one app if it asks.</li>
 *   <li>A credential with <b>no read scope</b> gets a 403 regardless of parameters.</li>
 * </ul>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final BigQueryAuditLogRepository repository;
    private final AuditProperties props;

    public AuditLogService(BigQueryAuditLogRepository repository, AuditProperties props) {
        this.repository = repository;
        this.props = props;
    }

    public AuditLogPage query(AuthenticatedClient client, AuditLogQuery request) {
        Optional<SourceApp> scope = resolveScope(client, request.sourceApp());

        int limit = clampLimit(request.limit());
        Instant from = request.from() != null
                ? request.from()
                : Instant.now().minus(Duration.ofDays(props.queryLookbackDays()));

        BigQueryAuditLogRepository.Criteria criteria = new BigQueryAuditLogRepository.Criteria(
                scope,
                request.userId(),
                request.domain(),
                request.eventType() == null ? null : request.eventType().name(),
                from,
                request.before(),
                limit);

        List<AuditLogEntry> rows = repository.search(criteria);

        String nextBefore = rows.size() == limit ? rows.get(rows.size() - 1).occurredAt() : null;
        String scopeLabel = scope.map(SourceApp::appId).orElse("all");

        log.info("[AuditLog] served rows={} scope={} user={} domain={} eventType={} client={}",
                rows.size(), scopeLabel, request.userId(), request.domain(),
                request.eventType() == null ? null : request.eventType().name(), client.name());

        return new AuditLogPage(scopeLabel, rows.size(), rows, nextBefore);
    }

    private Optional<SourceApp> resolveScope(AuthenticatedClient client, Optional<SourceApp> requested) {
        if (client.isCrossApp()) {
            return requested; // empty => every app; present => that one app
        }
        if (client.boundApp().isEmpty()) {
            throw new ForbiddenScopeException("this credential has no audit-log read access");
        }
        SourceApp bound = client.boundApp().get();
        if (requested.isPresent() && requested.get() != bound) {
            throw new ForbiddenScopeException(
                    "this credential is scoped to '" + bound.appId() + "' and cannot read '"
                            + requested.get().appId() + "'");
        }
        return Optional.of(bound);
    }

    private int clampLimit(Integer requested) {
        int limit = requested == null ? props.queryDefaultLimit() : requested;
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, props.queryMaxLimit());
    }
}
