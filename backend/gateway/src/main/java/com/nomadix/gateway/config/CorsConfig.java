package com.nomadix.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        // Autorise le frontend Angular (et d'autres si nécessaire)
        corsConfig.setAllowedOrigins(Arrays.asList("http://localhost:4200", "http://localhost:8080"));
        // Autorise toutes les méthodes HTTP nécessaires
        corsConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        // Autorise tous les headers (y compris Authorization pour les tokens JWT)
        corsConfig.setAllowedHeaders(Arrays.asList("*"));
        // Important : doit être false si allowedOrigins contient "*"
        corsConfig.setAllowCredentials(true);
        // Durée de vie du cache preflight (1 heure) pour les performances
        corsConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        return new CorsWebFilter(source);
    }
}