package com.dashboard.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/", "/health", "/error",
            "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/api/v1/audit/postback", "/audit/postback", "/api/audit/postback"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiKeyAuthFilter apiKeyAuthFilter,
                                                   PostbackRateLimitFilter rateLimitFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
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

    /**
     * Ingest is cross-origin by nature: any deployment of a client app may post telemetry, and
     * we identify callers by inspecting the Origin rather than by refusing it. Credentials stay
     * off, so a wildcard origin cannot be used to ride a user's session — and Spring rejects the
     * wildcard-plus-credentials combination outright.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Client"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
