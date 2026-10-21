package com.nomadix.traveler.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Pont de publication du traveler-service (Pack H).
 * Fire-and-forget : RabbitMQ eteint = simple log, le service continue.
 */
@Component
public class PackHTravelerEventBridge {

    private static volatile RabbitTemplate rabbitRef;

    public PackHTravelerEventBridge(RabbitTemplate rabbit) {
        rabbitRef = rabbit;
    }

    public static void publish(String routingKey, String jsonPayload) {
        if (rabbitRef == null) {
            System.err.println("[PackH] RabbitTemplate pas encore pret, evenement perdu : " + routingKey);
            return;
        }
        try {
            rabbitRef.convertAndSend(PackHTravelerRabbitConfig.EVENTS_EXCHANGE, routingKey, jsonPayload);
            System.out.println("[PackH] publie " + routingKey + " -> " + jsonPayload);
        } catch (Exception e) {
            System.err.println("[PackH] publication " + routingKey + " impossible : " + e.getMessage());
        }
    }
}