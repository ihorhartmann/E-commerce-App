package com.hartmann.infrastructure.config;

import com.hartmann.adapter.in.http.security.ProblemAccessDeniedHandler;
import com.hartmann.adapter.in.http.security.ProblemAuthenticationEntryPoint;
import com.hartmann.infrastructure.security.RolesClaimGrantedAuthoritiesConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * OAuth2 resource server. Customers never call this service: everything they need about a payment or refund comes from
 * the order-service, and the gateway doesn't route here except for the Stripe webhook. So the read endpoints are for
 * admins (diagnostics, e2e tests) only, and the rest are machine-to-machine calls, authenticated by the calling
 * service's own token.
 */
@Configuration
public class SecurityConfig {
    private static final String ERROR_PATH = "/error";
    private static final String PAYMENTS_PATH = "/api/v1/payments";
    private static final String REFUNDS_PATH = "/api/v1/refunds";
    private static final String STRIPE_WEBHOOK_PATH = "/api/v1/webhooks/stripe";
    private static final String ADMIN_ROLE = "ADMIN";
    // held by the order-service's own client (client credentials), never by a user
    private static final String PAYMENT_SUBMIT_ROLE = "PAYMENT_SUBMIT";

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        // 401 and 403 get the same ProblemDetail body as every other error response
        var authenticationEntryPoint = new ProblemAuthenticationEntryPoint(handlerExceptionResolver);
        var accessDeniedHandler = new ProblemAccessDeniedHandler(handlerExceptionResolver);
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(ERROR_PATH).permitAll()
                        // authenticated by the Stripe signature, not by a user token
                        .requestMatchers(HttpMethod.POST, STRIPE_WEBHOOK_PATH).permitAll()
                        // synchronous call from the order-service with its own token, never a user's; not exposed by the gateway
                        .requestMatchers(HttpMethod.POST, PAYMENTS_PATH).hasRole(PAYMENT_SUBMIT_ROLE)
                        .requestMatchers(HttpMethod.GET, PAYMENTS_PATH + "/**").hasRole(ADMIN_ROLE)
                        .requestMatchers(HttpMethod.GET, REFUNDS_PATH + "/**").hasRole(ADMIN_ROLE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // stateless Bearer-token API: no session, and no cookie a cross-site request could ride on
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * Keys come from the internal JWKS endpoint, but the issuer is checked against the public realm URL - the one
     * Keycloak always writes into {@code iss}. The keys are fetched lazily and cached, so the startup doesn't depend on
     * Keycloak being up.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${oidc.jwks-uri}") String jwksUri,
            @Value("${oidc.issuer}") String issuer
    ) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(@Value("${oidc.roles-claim}") String rolesClaim) {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RolesClaimGrantedAuthoritiesConverter(rolesClaim));
        return converter;
    }
}
