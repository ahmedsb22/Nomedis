package com.nomadix.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nomadix.booking.entity.Booking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publication des evenements de reservation (fire-and-forget).
 *
 * <p>Une panne RabbitMQ ne doit jamais annuler une reservation : la
 * publication est protegee par un {@code try/catch} et se contente d'un log
 * d'avertissement. Le canal asynchrone est un bonus, pas la source de verite.</p>
 */
@Component
public class BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BookingEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public BookingEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /** A appeler apres la sauvegarde (et donc le commit) de la reservation. */
    public void bookingCreated(Booking booking) {
        publish(BookingRabbitConfig.KEY_BOOKING_CREATED, booking);
    }

    /** A appeler apres la sauvegarde du statut CANCELLED. */
    public void bookingCancelled(Booking booking) {
        publish(BookingRabbitConfig.KEY_BOOKING_CANCELLED, booking);
    }

    private void publish(String routingKey, Booking booking) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", routingKey);
        node.put("bookingId", booking.getId());
        node.put("travelerId", booking.getTravelerId());
        node.put("flightId", booking.getFlightId());
        node.put("status", booking.getStatus());
        if (booking.getTotalPrice() != null) {
            node.put("totalPrice", booking.getTotalPrice());
        }
        node.put("occurredAt", Instant.now().toString());

        try {
            String json = objectMapper.writeValueAsString(node);
            publishRaw(routingKey, json);
        } catch (Exception e) {
            log.warn("[RABBITMQ] evenement {} non publie : {}", routingKey, e.getMessage());
        }
    }

    /** Utilise par l'endpoint de demonstration /api/packh/booking-events/emit. */
    public boolean publishRaw(String routingKey, String jsonPayload) {
        try {
            rabbitTemplate.convertAndSend(BookingRabbitConfig.EVENTS_EXCHANGE, routingKey, jsonPayload);
            log.info("[RABBITMQ] publie {} -> {}", routingKey, jsonPayload);
            return true;
        } catch (Exception e) {
            log.warn("[RABBITMQ] publication {} impossible (service toujours disponible) : {}",
                    routingKey, e.getMessage());
            return false;
        }
    }
}
