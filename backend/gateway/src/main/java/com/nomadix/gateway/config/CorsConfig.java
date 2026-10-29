package com.nomadix.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS de la Gateway, applique <b>avant</b> la chaine de securite.
 *
 * <p>Pourquoi ce filtre en plus du {@code spring.cloud.gateway.globalcors} ?</p>
 *
 * <p>Spring Cloud Gateway applique {@code globalcors} dans le
 * {@code RoutePredicateHandlerMapping}, donc <b>apres</b> les WebFilters de
 * Spring Security. Consequence : quand la Gateway refuse une requete (401 jeton
 * absent/expire, 403 role insuffisant), la reponse est ecrite directement par le
 * filtre de securite et ne traverse jamais le handler CORS. Le navigateur ne
 * recoit alors aucun {@code Access-Control-Allow-Origin} et masque le vrai
 * message derriere une erreur CORS (“TypeError: NetworkError”).</p>
 *
 * <p>Ce {@link CorsWebFilter} est enregistre a l'ordre le plus prioritaire : il
 * repond lui-meme aux preflights {@code OPTIONS} (qui ne partent donc plus vers
 * les microservices, ou ils finissaient en 401/403), et il ajoute les en-tetes
 * CORS a <b>toutes</b> les reponses, y compris les 401/403 du resource server.</p>
 *
 * <p>Les origines doivent rester alignees avec {@code globalcors} dans
 * {@code application.yml}. On autorise des <b>patterns</b> (n'importe quel port
 * de localhost / 127.0.0.1) au lieu d'une liste figee : le frontend Angular
 * (4200 en dev, 8090 en Docker) et les Swagger UI (8081-8084 + 3000 en dev,
 * 9081-9084 + 9089 + 3001 en Docker) changent de port selon le mode de
 * lancement, et une origine oubliee provoque un preflight 403 sans en-tete CORS.</p>
 */
@Configuration
public class CorsConfig {

    /** Toutes les origines locales, tous ports : frontend + Swagger UI. */
    private static final List<String> ALLOWED_ORIGIN_PATTERNS = List.of(
            "http://localhost:*",
            "http://127.0.0.1:*"
    );

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Patterns + credentials : compatible, contrairement a allowedOrigins("*").
        configuration.setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Authorization"));
        // Autorise l'envoi du jeton Bearer et des cookies : incompatible avec "*".
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter corsWebFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsWebFilter(corsConfigurationSource);
    }
}
