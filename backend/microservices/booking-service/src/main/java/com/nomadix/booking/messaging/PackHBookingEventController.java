package com.nomadix.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Endpoint d'emission du booking-service (meme convention que flight et traveler).
 * POST /api/packh/booking-events/emit
 *
 * <p>Permet de prouver le canal asynchrone depuis Swagger sans creer une vraie
 * reservation. Les vrais evenements {@code booking.created} /
 * {@code booking.cancelled} sont emis automatiquement par {@code BookingService}.</p>
 */
@RestController
@RequestMapping("/api/packh/booking-events")
@Tag(name = "Messaging (RabbitMQ)", description = "Emission d'evenements dans l'exchange nomadix.events")
public class PackHBookingEventController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BookingEventPublisher publisher;

    public PackHBookingEventController(BookingEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Operation(
            summary = "Publier un evenement booking.created",
            description = """
                    Publie dans l'exchange topic `nomadix.events` (routing key `booking.created`).
                    Corps optionnel : sans corps, une reservation d'exemple est generee.
                    Retourne `publishedTo` et `routingKey` comme preuve de l'emission.
                    Role ADMIN requis (regle Gateway et service).""")
    @ApiResponse(responseCode = "200", description = "Evenement publie (ou publication degradee si RabbitMQ est eteint)")
    @ApiResponse(responseCode = "401", description = "Jeton absent ou invalide")
    @ApiResponse(responseCode = "403", description = "Role ADMIN manquant")
    @PostMapping("/emit")
    public ResponseEntity<ObjectNode> emit(@RequestBody(required = false) Map<String, Object> body) {
        ObjectNode node = mapper.createObjectNode();
        if (body != null && !body.isEmpty()) {
            for (Map.Entry<String, Object> e : body.entrySet()) {
                node.set(e.getKey(), mapper.valueToTree(e.getValue()));
            }
        } else {
            node.put("bookingId", 0);
            node.put("travelerId", 1);
            node.put("flightId", 1);
            node.put("status", "CONFIRMED");
        }
        node.put("type", BookingRabbitConfig.KEY_BOOKING_CREATED);
        node.put("occurredAt", Instant.now().toString());

        String json;
        try {
            json = mapper.writeValueAsString(node);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(mapper.createObjectNode());
        }

        publisher.publishRaw(BookingRabbitConfig.KEY_BOOKING_CREATED, json);
        node.put("publishedTo", BookingRabbitConfig.EVENTS_EXCHANGE);
        node.put("routingKey", BookingRabbitConfig.KEY_BOOKING_CREATED);
        return ResponseEntity.ok(node);
    }
}
