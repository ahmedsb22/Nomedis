package com.nomadix.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Securite de la Gateway (point d'entree unique du frontend).
 *
 * <p>La Gateway est un <b>OAuth2 Resource Server</b> : elle ne delivre pas de
 * jeton, elle valide ceux emis par Keycloak puis laisse passer la requete vers
 * le microservice cible en conservant l'en-tete {@code Authorization}.</p>
 *
 * <p>Deux niveaux de protection sont appliques :</p>
 * <ul>
 *   <li><b>authentification</b> : tout {@code /api/**} exige un JWT valide ;</li>
 *   <li><b>autorisation</b> : les ecritures du catalogue et de l'administration
 *       exigent le role {@code ADMIN}, les lectures publiques restent ouvertes.</li>
 * </ul>
 *
 * <p>Les microservices revalident le meme jeton (defense en profondeur) : la
 * Gateway n'est jamais le seul rempart.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final String issuer;
    private final String jwkSetUri;
    private final String audience;

    public SecurityConfig(@Value("${nomadix.keycloak.issuer}") String issuer,
                          @Value("${nomadix.keycloak.jwk-set-uri}") String jwkSetUri,
                          @Value("${nomadix.keycloak.audience}") String audience) {
        this.issuer = issuer;
        this.jwkSetUri = jwkSetUri;
        this.audience = audience;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // Branche le CorsConfigurationSource de CorsConfig dans la chaine de securite :
                // sans cela, les 401/403 ecrits par le resource server n'ont pas d'en-tetes CORS.
                .cors(Customizer.withDefaults())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Preflight CORS : jamais authentifie (le navigateur n'envoie pas de jeton)
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        // Pages de sante et racine : publiques
                        .pathMatchers("/", "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Catalogue en lecture : public (site consultable sans compte)
                        .pathMatchers(HttpMethod.GET, "/api/flights", "/api/flights/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/conferences", "/api/conferences/**").permitAll()
                        // Ecriture du catalogue : administrateur uniquement
                        .pathMatchers(HttpMethod.POST, "/api/flights", "/api/flights/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/flights/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PATCH, "/api/flights/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/flights/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST, "/api/conferences", "/api/conferences/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/conferences/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PATCH, "/api/conferences/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, "/api/conferences/**").hasRole("ADMIN")
                        // Service avance de profiling et emission d'evenements : admin
                        .pathMatchers("/api/travelers/profiles/**").hasRole("ADMIN")
                        .pathMatchers("/api/packh/**").hasRole("ADMIN")
                        // Supervision : admin
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        // Tout le reste (voyageurs, reservations, favoris, notifications) : authentifie
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .build();
    }

    /**
     * Decodeur JWT construit sur le JWKS de Keycloak.
     *
     * <p>On n'utilise volontairement pas {@code issuer-uri} seul : Spring irait
     * chercher la configuration OpenID au demarrage et la Gateway ne demarrerait
     * plus si Keycloak est arrete. Ici la signature est verifiee a chaque requete
     * (les cles sont mises en cache), et l'emetteur + l'audience sont valides.</p>
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }

    @Bean
    public Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(KeycloakRoles.converter());
        converter.setPrincipalClaimName("preferred_username");
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    @Bean
    public ServerAuthenticationEntryPoint unauthorizedEntryPoint() {
        return (exchange, exception) -> writeError(exchange, HttpStatus.UNAUTHORIZED,
                "Jeton absent, expire ou invalide : connectez-vous via Keycloak (" + issuer + ")");
    }

    @Bean
    public ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, denied) -> writeError(exchange, HttpStatus.FORBIDDEN,
                "Role insuffisant : cette operation exige le role ADMIN");
    }

    /** Reponse JSON homogene avec le reste de l'API (au lieu d'une page HTML). */
    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":" + status.value()
                + ",\"error\":\"" + status.getReasonPhrase()
                + "\",\"message\":\"" + message
                + "\",\"path\":\"" + exchange.getRequest().getPath().value() + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
