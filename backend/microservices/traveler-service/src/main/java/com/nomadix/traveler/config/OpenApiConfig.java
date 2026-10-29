package com.nomadix.traveler.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Specification OpenAPI du Traveler-Service.
 *
 * <p>Le bouton <b>Authorize</b> de Swagger UI propose deux methodes :</p>
 * <ol>
 *   <li><b>keycloak</b> : flux Authorization Code + PKCE (client public
 *       {@code nomadix-swagger}) ;</li>
 *   <li><b>bearer</b> : collage direct d'un access token (utile en script).</li>
 * </ol>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI travelerOpenAPI(@Value("${nomadix.keycloak.issuer}") String issuer) {
        String authUrl = issuer + "/protocol/openid-connect/auth";
        String tokenUrl = issuer + "/protocol/openid-connect/token";

        return new OpenAPI()
                .info(new Info()
                        .title("Nomadix - Traveler Service API")
                        .version("3.0.0")
                        .description("""
                                Gestion des voyageurs, des vols favoris (enrichissement OpenFeign)
                                et du moteur de profilage (service avance).

                                **Authentification (Keycloak)**
                                1. Cliquez sur *Authorize*
                                2. Choisissez le flux `keycloak` puis le scope `openid profile email`
                                3. Connectez-vous avec un compte de test :
                                   - `sami` / `sami123` (role TRAVELER)
                                   - `admin` / `admin123` (roles ADMIN + TRAVELER)

                                Les lectures de vols sont publiques ; le profilage est reserve a ADMIN.

                                ---

                                ## Scenario RabbitMQ : `traveler.registered`
                                `POST /api/packh/traveler-events/emit` (role ADMIN) publie dans
                                l'exchange topic `nomadix.events`.

                                **Lecture dans l'UI RabbitMQ** (http://localhost:15672) :
                                - *Queues* > `notification.events` : `Ready` revient a 0 apres
                                  acquittement, `Consumers` = 1 ;
                                - le log du consommateur affiche `recu traveler.registered` ;
                                - l'evenement est archive dans `notifications.json`.

                                La documentation complete des scenarios RabbitMQ et OpenFeign
                                se trouve dans le Swagger du **Booking-Service**.
                                """)
                        .contact(new Contact().name("Equipe Nomadix")))
                // L'ordre compte : Swagger UI utilise le PREMIER serveur par defaut.
                // En deploiement Docker Compose la Gateway est publiee sur 9089
                // (le port interne 8089 n'existe pas sur la machine hote) : la
                // placer en tete evite les "TypeError: NetworkError" du bouton
                // "Try it out".
                .addServersItem(new Server().url("http://localhost:9089")
                        .description("Via l'API Gateway (Docker Compose - recommande)"))
                .addServersItem(new Server().url("http://localhost:9081")
                        .description("Acces direct au conteneur traveler-service"))
                .addServersItem(new Server().url("http://localhost:8089")
                        .description("Via l'API Gateway (services lances hors Docker)"))
                .addServersItem(new Server().url("http://localhost:8081")
                        .description("Acces direct au microservice (dev local)"))
                .components(new Components()
                        .addSecuritySchemes("keycloak", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("Keycloak - Authorization Code + PKCE")
                                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                        .authorizationUrl(authUrl)
                                        .tokenUrl(tokenUrl)
                                        .scopes(new Scopes()
                                                .addString("openid", "Identite de l'utilisateur")
                                                .addString("profile", "Nom, prenom, username")
                                                .addString("email", "Adresse e-mail")))))
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Collez directement un access token Keycloak")))
                .addSecurityItem(new SecurityRequirement().addList("keycloak"));
    }
}
