package com.nomadix.flight.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Pont de publication du flight-service (Pack H).
 * Fire-and-forget : si RabbitMQ est eteint, on ecrit juste une ligne
 * dans les logs -> le vol est cree quand meme.
 */
@Component
public class PackHFlightEventBridge {

    private static volatile RabbitTemplate rabbitRef;

    public PackHFlightEventBridge(RabbitTemplate rabbit) {
        rabbitRef = rabbit;
    }

    public static void publish(String routingKey, String jsonPayload) {
        if (rabbitRef == null) {
            System.err.println("[PackH] RabbitTemplate pas encore pret, evenement perdu : " + routingKey);
            return;
        }
        try {
            rabbitRef.convertAndSend(PackHFlightRabbitConfig.EVENTS_EXCHANGE, routingKey, jsonPayload);
            System.out.println("[PackH] publie " + routingKey + " -> " + jsonPayload);
        } catch (Exception e) {
            System.err.println("[PackH] publication " + routingKey + " impossible : " + e.getMessage());
        }
    }
}