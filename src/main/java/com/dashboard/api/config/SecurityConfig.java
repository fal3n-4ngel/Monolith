package com.dashboard.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Every caller of every remaining endpoint is a server (Continuum's backend, this API's own
 * clients) — none of it is fetched cross-origin from a browser, so there's no CORS
 * configuration here. If a browser-facing endpoint ever gets added, that's the moment to add it
 * back deliberately, not by default.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/", "/health", "/error",
            "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
            "/actuator/health", "/actuator/health/**", "/actuator/info",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiKeyAuthFilter apiKeyAuthFilter,
                                                   PostbackRateLimitFilter rateLimitFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // Rate limiting runs first so abusive traffic is shed before any auth work.
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(
            @Value("${dashboard.api-key:}") String dashboardApiKey,
            @Value("${continuum.api-key:}") String continuumApiKey,
            @Value("${dashboard.allowed-email:}") String allowedEmail) {
        return new ApiKeyAuthFilter(dashboardApiKey, continuumApiKey, allowedEmail);
    }

    @Bean
    public PostbackRateLimitFilter postbackRateLimitFilter(AuditProperties props) {
        return new PostbackRateLimitFilter(props.rateLimitPerMinute());
    }
}
