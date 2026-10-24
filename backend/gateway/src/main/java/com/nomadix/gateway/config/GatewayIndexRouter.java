package com.nomadix.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * Page racine de la Gateway : JSON de bienvenue avec la carte des routes.
 * (Une API Gateway reactive n'a pas de page d'accueil par defaut.)
 */
@Configuration
public class GatewayIndexRouter {

    @Bean
    public RouterFunction<ServerResponse> gatewayIndex() {
        return route(GET("/"), req -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "NOMADIX API Gateway");
            body.put("status", "UP");
            body.put("port", 8089);
            Map<String, String> routes = new LinkedHashMap<>();
            routes.put("travelers",     "/api/travelers/**      -> lb://TRAVELER-SERVICE (8081)");
            routes.put("profiling",     "/api/travelers/profiles/**  (service avance)");
            routes.put("flights",       "/api/flights/**        -> lb://FLIGHT-SERVICE (8082)");
            routes.put("bookings",      "/api/bookings/**       -> lb://BOOKING-SERVICE (8083)");
            routes.put("notifications", "/api/notifications/**  -> lb://NOTIFICATION-SERVICE (8084)");
            routes.put("conferences",   "/api/conferences/**    -> lb://CONFERENCE-SERVICE (3000)");
            body.put("routes", routes);
            body.put("eureka", "http://localhost:8761");
            body.put("routesActuator", "/actuator/gateway/routes");
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        });
    }
}