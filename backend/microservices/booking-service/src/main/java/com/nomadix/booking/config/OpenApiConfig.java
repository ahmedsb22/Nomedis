package com.nomadix.booking.config;

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

/** Specification OpenAPI du Booking-Service (orchestration de la saga). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bookingOpenAPI(@Value("${nomadix.keycloak.issuer}") String issuer) {
        String authUrl = issuer + "/protocol/openid-connect/auth";
        String tokenUrl = issuer + "/protocol/openid-connect/token";

        return new OpenAPI()
                .info(new Info()
                        .title("Nomadix - Booking Service API")
                        .version("3.0.0")
                        .description("""
                                Reservation de vols : orchestration synchrone (OpenFeign) vers
                                Traveler-Service et Flight-Service, avec compensation si une
                                etape echoue.

                                **Tous les endpoints exigent un jeton Keycloak.**
                                Comptes de test : `sami` / `sami123` (TRAVELER),
                                `admin` / `admin123` (ADMIN).

                                **Codes retour a connaitre**
                                - `201` reservation creee (place decrementee)
                                - `400` statut invalide
                                - `404` voyageur, vol ou reservation inexistant
                                - `409` vol complet
                                - `503` Flight/Traveler indisponible (circuit breaker OPEN)

                                ---

                                ## 1. Scenarios RabbitMQ (exchange `nomadix.events`)

                                **Topologie declaree** (`backend/messaging/rabbitmq/definitions.json`) :

                                | Element | Nom | Role |
                                | --- | --- | --- |
                                | Exchange topic | `nomadix.events` | evenements metier (`flight.*`, `traveler.*`, `booking.*`) |
                                | Queue durable | `notification.events` | boite aux lettres du consommateur Python |
                                | Exchange dead-letter | `nomadix.dlx` | recoit les messages refuses |
                                | Queue dead-letter | `notification.events.dlq` | archive des messages empoisonnes |

                                `notification.events` porte `x-dead-letter-exchange=nomadix.dlx`
                                et `x-dead-letter-routing-key=notification.events.dlq`.

                                ### 1.1 flight.created
                                1. Authorize, puis `POST /api/packh/flight-events/emit` (Swagger Flight-Service).
                                2. Reponse : `publishedTo=nomadix.events`, `routingKey=flight.created`.
                                3. UI RabbitMQ > **Exchanges** > `nomadix.events` : *Message rates* montre un `publish`.
                                4. UI RabbitMQ > **Queues** > `notification.events` : `Ready` revient a 0
                                   (le consommateur acquitte), le log affiche `recu flight.created`.
                                Interpretation : l'evenement a bien ete **consomme**, pas seulement emis.

                                ### 1.2 traveler.registered
                                Meme methode avec `POST /api/packh/traveler-events/emit` (Swagger Traveler).
                                Attendu : routing key `traveler.registered`, message acquitte, ligne dans
                                `backend/microservices/notification/notifications.json`.

                                ### 1.3 booking.created (succes de la saga)
                                1. `POST /api/bookings?travelerId=1&flightId=1`.
                                2. Feign valide le voyageur, decremente une place, la reservation est
                                   enregistree, **puis** `booking.created` est publie.
                                3. UI RabbitMQ > Queues : pic `Ready` puis retour a 0.
                                Interpretation : la publication n'a lieu qu'apres le succes metier ;
                                si l'etape 2 echoue, **aucun** message n'est emis.

                                ### 1.4 booking.cancelled
                                1. Creer une reservation, puis `DELETE /api/bookings/{id}`.
                                2. Reponse `204`, place restituee sur le vol.
                                3. UI RabbitMQ : `booking.cancelled` consomme ; le corps contient
                                   `bookingId`, `status=CANCELLED`, `occurredAt`.

                                ### 1.5 Boite aux lettres (durabilite / consommateur arrete)
                                1. `docker compose stop notification-consumer`.
                                2. Emettre 3 a 5 evenements (vols, voyageurs, reservations).
                                3. UI RabbitMQ > Queues > `notification.events` :
                                   `Ready` augmente, `Consumers` = 0, `Unacked` = 0.
                                4. `docker compose start notification-consumer`.
                                5. `Ready` retombe a 0 et le backlog est traite d'un coup.
                                Interpretation : la file durable joue le role de boite vocale ;
                                aucun message n'est perdu pendant l'arret.

                                ### 1.6 Mode degrade (RabbitMQ eteint)
                                1. `docker compose stop rabbitmq`.
                                2. `POST /api/bookings?travelerId=1&flightId=1`.
                                3. Resultat attendu : **201 Created**, la reservation est enregistree.
                                4. Le log du booking-service contient un `WARN [RABBITMQ] publication ... impossible`.
                                5. Relancer rabbitmq : les evenements suivants repartent normalement.
                                Interpretation : pattern *fire-and-forget*, le broker n'est jamais
                                sur le chemin critique d'une reservation.

                                ### 1.7 DLX / DLQ (message empoisonne)
                                1. `POST /api/packh/rabbitmq/poison` : publie un corps non-JSON
                                   sur `booking.poison`.
                                2. UI RabbitMQ > Queues > `notification.events` : `Ready` ne boucle pas.
                                3. `notification.events.dlq` : `Ready` = 1.
                                4. *Get messages* sur la DLQ : le corps invalide et l'en-tete
                                   `x-death` (`reason=rejected`, `queue=notification.events`)
                                   prouvent le dead-lettering.
                                Interpretation : un message impossible a traiter est isole pour
                                analyse/rejeu, la file principale continue de vivre.

                                ---

                                ## 2. Scenarios OpenFeign (appels synchrones)

                                Endpoint de diagnostic : `GET /api/bookings/integrations/feign`.

                                ### 2.1 Nominal
                                `travelerId=1&flightId=1` -> `travelerService.reachable=true`,
                                `flightService.reachable=true`, `availableSeats` renvoye par le vol.
                                Interpretation : les deux contrats Feign et Eureka fonctionnent.

                                ### 2.2 Ressource absente (404 metier)
                                `travelerId=99999` -> `reachable=true`, `found=false`, `httpStatus=404`.
                                Interpretation : un 404 est une reponse metier, pas une panne ;
                                il n'ouvre pas le circuit breaker.

                                ### 2.3 Service indisponible (503 + circuit breaker)
                                1. `docker compose stop flight-service`.
                                2. Appeler l'endpoint de diagnostic plusieurs fois.
                                3. Avant le seuil : `reachable=false` avec un timeout (2 s / 3 s).
                                4. Apres 5 appels en echec : `Circuit breaker OPEN`.
                                5. `GET http://localhost:8xxx/actuator/health` : composant
                                   `circuitBreakers.flight-service.state=OPEN`.
                                6. `docker compose start flight-service`, attendre 10 s :
                                   etat `HALF_OPEN` puis `CLOSED`.
                                Interpretation : la panne est contenue (fail-fast) au lieu de
                                bloquer chaque requete pendant 60 s.

                                ### 2.4 Compensation de la saga
                                Avec `flight-service` arrete, `POST /api/bookings` renvoie `503`
                                et **aucune** ligne n'est creee. Avec un vol complet, la reponse
                                est `409` : la place n'est pas prelevee.
                                """)
                        .contact(new Contact().name("Equipe Nomadix")))
                .addServersItem(new Server().url("http://localhost:9089")
                        .description("Via l'API Gateway (Docker Compose)"))
                .addServersItem(new Server().url("http://localhost:9083")
                        .description("Acces direct au conteneur booking-service"))
                .addServersItem(new Server().url("http://localhost:8089")
                        .description("Via l'API Gateway (dev local)"))
                .addServersItem(new Server().url("http://localhost:8083")
                        .description("Acces direct au microservice (dev)"))
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
