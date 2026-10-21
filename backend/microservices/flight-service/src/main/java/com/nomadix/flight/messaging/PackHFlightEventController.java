package com.nomadix.flight.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Endpoint d'emission du flight-service (Pack H).
 * POST /api/packh/flight-events/emit
 *   - corps JSON optionnel (ex: {"flightNumber":"TN999"})
 *   - sans corps : un vol d'exemple est genere.
 * Publie "flight.created" dans nomadix.events puis renvoie la preuve.
 */
@RestController
@RequestMapping("/api/packh/flight-events")
public class PackHFlightEventController {

    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/emit")
    public ResponseEntity<ObjectNode> emit(@RequestBody(required = false) Map<String, Object> body) {
        ObjectNode node = mapper.createObjectNode();
        if (body != null && !body.isEmpty()) {
            for (Map.Entry<String, Object> e : body.entrySet()) {
                node.set(e.getKey(), mapper.valueToTree(e.getValue()));
            }
        } else {
            node.put("flightNumber", "TNHX-" + System.currentTimeMillis());
            node.put("departureCity", "Tunis");
            node.put("arrivalCity", "Paris");
            node.put("availableSeats", 120);
        }
        node.put("type", "flight.created");
        node.put("occurredAt", Instant.now().toString());
        String json;
        try {
            json = mapper.writeValueAsString(node);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(mapper.createObjectNode());
        }
        PackHFlightEventBridge.publish(PackHFlightRabbitConfig.KEY_FLIGHT_CREATED, json);
        node.put("publishedTo", PackHFlightRabbitConfig.EVENTS_EXCHANGE);
        node.put("routingKey", PackHFlightRabbitConfig.KEY_FLIGHT_CREATED);
        return ResponseEntity.ok(node);
    }
}