package com.ledgerflow.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API for Swagger UI: a title, what the money endpoints
 * expect, and a bearer-token scheme so "Authorize" works in the browser.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI ledgerFlowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LedgerFlow API")
                        .version("v1")
                        .description("""
                                Wallets, transfers, payments and the ledger behind them.

                                Sign in with POST /api/v1/auth/login, then send the token as \
                                `Authorization: Bearer <token>`. Every request that moves money \
                                needs an `Idempotency-Key` header: sending the same key again \
                                returns the first result instead of moving money twice. Amounts \
                                are whole minor units (cents). Lists are paged with `cursor`.""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
