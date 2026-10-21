package com.nomadix.flight.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaration RabbitMQ du flight-service (Pack H).
 * Le flight-service est PRODUCTEUR : il publie "flight.created" dans
 * l'exchange topic nomadix.events. Il n'ecoute aucune queue.
 */
@Configuration
public class PackHFlightRabbitConfig {

    public static final String EVENTS_EXCHANGE = "nomadix.events";
    public static final String KEY_FLIGHT_CREATED = "flight.created";

    @Bean
    public TopicExchange packHFlightEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}