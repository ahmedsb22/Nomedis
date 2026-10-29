package com.nomadix.traveler.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Endpoint d'emission du traveler-service (Pack H).
 * POST /api/packh/traveler-events/emit
 * Publie l'evenement "traveler.registered" dans nomadix.events.
 */
@RestController
@RequestMapping("/api/packh/traveler-events")
@Tag(name = "Messaging (RabbitMQ)", description = "Emission d'evenements dans l'exchange nomadix.events")
public class PackHTravelerEventController {

    private final ObjectMapper mapper = new ObjectMapper();

    @Operation(
            summary = "Publier un evenement traveler.registered",
            description = """
                    Publie dans l'exchange topic `nomadix.events` (routing key `traveler.registered`).
                    Corps optionnel : sans corps, un voyageur d'exemple est genere.
                    Role ADMIN requis.
                    """)
    @PostMapping("/emit")
    public ResponseEntity<ObjectNode> emit(@RequestBody(required = false) Map<String, Object> body) {
        ObjectNode node = mapper.createObjectNode();
        if (body != null && !body.isEmpty()) {
            for (Map.Entry<String, Object> e : body.entrySet()) {
                node.set(e.getKey(), mapper.valueToTree(e.getValue()));
            }
        } else {
            node.put("travelerId", "TRVHX-" + System.currentTimeMillis());
            node.put("fullName", "Voyageur Test");
            node.put("email", "test.packh@nomadix.tn");
        }
        node.put("type", "traveler.registered");
        node.put("occurredAt", Instant.now().toString());
        String json;
        try {
            json = mapper.writeValueAsString(node);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(mapper.createObjectNode());
        }
        PackHTravelerEventBridge.publish(PackHTravelerRabbitConfig.KEY_TRAVELER_REGISTERED, json);
        node.put("publishedTo", PackHTravelerRabbitConfig.EVENTS_EXCHANGE);
        node.put("routingKey", PackHTravelerRabbitConfig.KEY_TRAVELER_REGISTERED);
        return ResponseEntity.ok(node);
    }
}
