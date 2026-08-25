package com.dashboard.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Monolith Audit & Telemetry API")
                        .description("""
                                Central audit log receiver for Continuum Home and integrated personal applications.

                                **Ingest** (`POST /api/v1/audit/postback`) is unauthenticated and rate limited.
                                It returns `202 Accepted` — events are persisted asynchronously, so the response
                                is an acknowledgement, not a durability guarantee.

                                **Query** (`GET /api/v1/audit/logs`) requires a bearer API key or an allow-listed
                                Google ID token.""")
                        .version("3.0.0")
                        .contact(new Contact()
                                .name("Dashboard Admin")
                                .url("https://www.adithyakrishnan.com"))
                        .license(new License().name("MIT")))
                // Applied globally; the ingest endpoint opts out with @SecurityRequirements.
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("API_KEY")
                                        .description("API key from Secret Manager (API_KEY / CONTINUUM_API_KEY), "
                                                + "or a Google ID token for the allow-listed identity.")));
    }
}
