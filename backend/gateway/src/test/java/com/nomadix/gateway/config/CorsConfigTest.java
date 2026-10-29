package com.nomadix.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du CORS de la Gateway.
 *
 * <p>Le point critique corrige ici : un 401/403 ecrit par Spring Security doit
 * quand meme porter {@code Access-Control-Allow-Origin}, sinon le navigateur
 * remplace le vrai message par une erreur CORS opaque.</p>
 */
class CorsConfigTest {

    private final CorsConfig config = new CorsConfig();
    private final CorsWebFilter filter = config.corsWebFilter(config.corsConfigurationSource());

    @Test
    void preflightFromSwaggerIsAnsweredBeforeSecurity() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.options("http://localhost:8089/api/travelers/profiles/1/recompute")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8081")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                        .build());

        filter.filter(exchange, ex -> {
            throw new AssertionError("Le preflight ne doit jamais atteindre la chaine de securite");
        }).block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertEquals("http://localhost:8081", headers.getAccessControlAllowOrigin());
        assertTrue(headers.getAccessControlAllowMethods().contains(HttpMethod.POST));
        // Le statut est laisse a null par le filtre (WebFlux renvoie 200 par defaut
        // quand la reponse est vide) : l'important est que la chaine ne soit pas appelee.
    }

    @Test
    void unauthorizedResponseKeepsCorsHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost:8089/api/travelers/profiles/1/recompute")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8081")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .build());

        filter.filter(exchange, ex -> {
            // Simule exactement ce que fait SecurityConfig quand le jeton est absent/expire.
            ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return ex.getResponse().setComplete();
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("http://localhost:8081",
                exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
        assertEquals(Boolean.TRUE,
                exchange.getResponse().getHeaders().getAccessControlAllowCredentials());
    }

    @Test
    void forbiddenResponseKeepsCorsHeadersForTheAngularOrigin() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost:8089/api/packh/flight-events/emit")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .build());

        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return ex.getResponse().setComplete();
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals("http://localhost:4200",
                exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
    }

    @Test
    void unknownOriginIsRejectedWithoutCorsHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost:8089/api/flights/1")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .build());

        filter.filter(exchange, ex -> {
            throw new AssertionError("Une origine inconnue ne doit pas atteindre la chaine");
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertNull(exchange.getResponse().getHeaders().getAccessControlAllowOrigin());
    }
}
