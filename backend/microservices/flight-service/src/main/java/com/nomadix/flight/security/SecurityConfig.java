package com.nomadix.flight.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Securite du Flight-Service.
 *
 * <p>Regles metier :</p>
 * <ul>
 *   <li>lecture du catalogue ({@code GET /api/flights}) : <b>publique</b> ;</li>
 *   <li>ecriture (creation, mise a jour, suppression) : <b>ADMIN</b> ;</li>
 *   <li>reservation / restitution d'une place : <b>authentifie</b> (appele par
 *       le Booking-Service avec le jeton du voyageur) ;</li>
 *   <li>emission d'evenements RabbitMQ : <b>ADMIN</b>.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Preflight CORS : jamais authentifie (le navigateur n'envoie pas de jeton)
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/packh/**").hasRole("ADMIN")
                        // Stock de sieges : appele en interne par Booking-Service (jeton du voyageur)
                        .requestMatchers(HttpMethod.POST, "/api/flights/*/seats/reserve", "/api/flights/*/seats/release")
                        .authenticated()
                        // Catalogue : lecture publique, ecriture administrateur
                        .requestMatchers(HttpMethod.GET, "/api/flights", "/api/flights/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/flights", "/api/flights/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/flights/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/flights/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/flights/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint((request, response, ex) -> writeError(response, request.getRequestURI(),
                                HttpStatus.UNAUTHORIZED, "Jeton absent, expire ou invalide (Keycloak)"))
                        .accessDeniedHandler((request, response, ex) -> writeError(response, request.getRequestURI(),
                                HttpStatus.FORBIDDEN, "Role insuffisant : operation reservee au role ADMIN")))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> writeError(response, request.getRequestURI(),
                                HttpStatus.UNAUTHORIZED, "Jeton absent, expire ou invalide (Keycloak)"))
                        .accessDeniedHandler((request, response, ex) -> writeError(response, request.getRequestURI(),
                                HttpStatus.FORBIDDEN, "Role insuffisant : operation reservee au role ADMIN")));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(KeycloakRoles.converter());
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    private void writeError(HttpServletResponse response, String path,
                            HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status.value()
                + ",\"error\":\"" + status.getReasonPhrase()
                + "\",\"message\":\"" + message
                + "\",\"path\":\"" + path + "\"}");
    }
}
