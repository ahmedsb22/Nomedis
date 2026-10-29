package com.nomadix.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Scenarios RabbitMQ observables depuis Swagger.
 *
 * <p>Le role de ce controleur est pedagogique : rendre visible dans l'UI
 * RabbitMQ ce qui se passe quand un message ne peut pas etre traite
 * (message empoisonne -&gt; DLX -&gt; DLQ).</p>
 */
@RestController
@RequestMapping("/api/packh/rabbitmq")
@Tag(name = "Messaging (RabbitMQ / DLQ)",
        description = "Scenarios de test RabbitMQ : emission, panne (fire-and-forget) et dead-letter queue")
public class RabbitScenarioController {

    private static final String DLX = "nomadix.dlx";
    private static final String DLQ = "notification.events.dlq";

    private final BookingEventPublisher publisher;
    private final ObjectMapper mapper = new ObjectMapper();

    public RabbitScenarioController(BookingEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Operation(
            summary = "Publier un message empoisonne (demonstration DLX/DLQ)",
            description = """
                    Publie volontairement un corps qui n'est **pas** du JSON valide
                    dans l'exchange `nomadix.events` (routing key `booking.poison`).

                    La file `notification.events` est liee a `booking.#` et possede
                    `x-dead-letter-exchange = nomadix.dlx`. Le consommateur Python
                    refuse le message (`basic_nack requeue=false`) : RabbitMQ le
                    deplace vers l'exchange `nomadix.dlx`, puis dans la file
                    `notification.events.dlq` avec l'en-tete `x-death`.

                    **Lecture dans l'UI RabbitMQ** (http://localhost:15672, onglet Queues) :
                    1. `notification.events` : Ready ne doit pas augmenter durablement ;
                    2. `notification.events.dlq` : Ready passe a 1 ;
                    3. ouvrir la file DLQ > *Get messages* : le corps invalide et
                       l'en-tete `x-death` (reason = rejected, queue = notification.events)
                       prouvent le dead-lettering ;
                    4. onglet Exchanges : `nomadix.dlx` est le point de passage.

                    Si RabbitMQ est arrete, reponse 200 avec `published = false` :
                    c'est le mode degrade (fire-and-forget), la reservation n'est pas bloquee.
                    """)
    @ApiResponse(responseCode = "200", description = "Message publie (ou publication degradee si RabbitMQ est eteint)")
    @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide")
    @ApiResponse(responseCode = "403", description = "Role ADMIN manquant")
    @PostMapping("/poison")
    public ResponseEntity<ObjectNode> poison() {
        String payload = "POISON-MESSAGE (not JSON) emis a " + Instant.now();
        boolean published = publisher.publishRaw("booking.poison", payload);

        ObjectNode response = mapper.createObjectNode();
        response.put("published", published);
        response.put("exchange", BookingRabbitConfig.EVENTS_EXCHANGE);
        response.put("routingKey", "booking.poison");
        response.put("deadLetterExchange", DLX);
        response.put("deadLetterQueue", DLQ);
        response.put("inspectInUi", "http://localhost:15672/#/queues/%2F/" + DLQ);
        response.put("expected", published
                ? "Le message quitte notification.events et apparait dans " + DLQ
                : "RabbitMQ indisponible : mode degrade, rien n'est publie");
        return ResponseEntity.ok(response);
    }
}
