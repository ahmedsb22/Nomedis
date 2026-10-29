package com.nomadix.booking.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaration RabbitMQ du booking-service.
 *
 * <p>Le service est <b>producteur</b> : il publie dans l'exchange topic
 * {@code nomadix.events} les evenements du cycle de vie d'une reservation.
 * Le consommateur Python ({@code packh_rabbit_consumer.py}) est lie a
 * {@code booking.#} et les stocke dans {@code notifications.json}.</p>
 */
@Configuration
public class BookingRabbitConfig {

    public static final String EVENTS_EXCHANGE = "nomadix.events";
    public static final String KEY_BOOKING_CREATED = "booking.created";
    public static final String KEY_BOOKING_CANCELLED = "booking.cancelled";

    @Bean
    public TopicExchange bookingEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}
