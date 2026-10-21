package com.nomadix.traveler.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaration RabbitMQ du traveler-service (Pack H).
 * Ce service est a la fois client Feign (vers flight) et ici PRODUCTEUR
 * RabbitMQ : les deux coexistent sans conflit (synchrone vs asynchrone).
 */
@Configuration
public class PackHTravelerRabbitConfig {

    public static final String EVENTS_EXCHANGE = "nomadix.events";
    public static final String KEY_TRAVELER_REGISTERED = "traveler.registered";

    @Bean
    public TopicExchange packHTravelerEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}