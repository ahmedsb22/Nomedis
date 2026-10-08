package com.nomadix.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                // Route vers le service Traveler
                .route("traveler-service", r -> r.path("/api/travelers/**")
                        .uri("lb://TRAVELER-SERVICE"))
                
                // Route vers le service Flight
                .route("flight-service", r -> r.path("/api/flights/**")
                        .uri("lb://FLIGHT-SERVICE"))
                
                // Route vers le service Booking
                .route("booking-service", r -> r.path("/api/bookings/**")
                        .uri("lb://BOOKING-SERVICE"))
                
                // Route vers le service Conference
                .route("conference-service", r -> r.path("/api/conferences/**")
                        .uri("lb://CONFERENCE-SERVICE"))
                
                // Route vers le service Notification
                .route("notification-service", r -> r.path("/api/notifications/**")
                        .uri("lb://NOTIFICATION-SERVICE"))
                
                .build();
    }
}