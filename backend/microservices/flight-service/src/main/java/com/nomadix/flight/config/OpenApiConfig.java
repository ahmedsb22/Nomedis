package com.nomadix.flight.config;

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

/** Specification OpenAPI du Flight-Service (catalogue + stock de sieges). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flightOpenAPI(@Value("${nomadix.keycloak.issuer}") String issuer) {
        String authUrl = issuer + "/protocol/openid-connect/auth";
        String tokenUrl = issuer + "/protocol/openid-connect/token";

        return new OpenAPI()
                .info(new Info()
                        .title("Nomadix - Flight Service API")
                        .version("3.0.0")
                        .description("""
                                Catalogue des vols et gestion transactionnelle du stock de sieges.

                                **Droits**
                                - `GET /api/flights/**` : public (aucun jeton)
                                - `POST/PUT/DELETE /api/flights/**` : role `ADMIN`
                                - `POST /api/flights/{id}/seats/reserve|release` : authentifie
                                  (appele par le Booking-Service avec le jeton du voyageur)

                                **Comptes de test** : `admin` / `admin123` (ADMIN),
                                `sami` / `sami123` (TRAVELER).

                                ---

                                ## Scenario RabbitMQ : `flight.created`
                                `POST /api/packh/flight-events/emit` (role ADMIN) publie dans
                                l'exchange topic `nomadix.events`.

                                **Lecture dans l'UI RabbitMQ** (http://localhost:15672) :
                                - *Exchanges* > `nomadix.events` : le debit de publication augmente ;
                                - *Queues* > `notification.events` : `Ready` revient a 0 car le
                                  consommateur Python acquitte le message ;
                                - le log du consommateur affiche `recu flight.created`.

                                La route complete (avec la saga, les autres evenements et la DLQ)
                                est documentee dans le Swagger du **Booking-Service**.
                                """)
                        .contact(new Contact().name("Equipe Nomadix")))
                // Premier serveur = choix par defaut de Swagger UI : la Gateway
                // publiee sur 9089 en Docker Compose (8089 n'existe pas cote hote).
                .addServersItem(new Server().url("http://localhost:9089")
                        .description("Via l'API Gateway (Docker Compose - recommande)"))
                .addServersItem(new Server().url("http://localhost:9082")
                        .description("Acces direct au conteneur flight-service"))
                .addServersItem(new Server().url("http://localhost:8089")
                        .description("Via l'API Gateway (services lances hors Docker)"))
                .addServersItem(new Server().url("http://localhost:8082")
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
