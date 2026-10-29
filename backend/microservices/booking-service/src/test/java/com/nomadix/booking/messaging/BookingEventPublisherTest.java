package com.nomadix.booking.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomadix.booking.entity.Booking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookingEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Booking sampleBooking() {
        Booking booking = new Booking();
        booking.setId(42L);
        booking.setTravelerId(1L);
        booking.setFlightId(2L);
        booking.setStatus("CONFIRMED");
        booking.setTotalPrice(new BigDecimal("350.50"));
        return booking;
    }

    @Test
    @DisplayName("booking.created est publie dans nomadix.events avec le detail de la reservation")
    void bookingCreatedIsPublished() throws Exception {
        BookingEventPublisher publisher = new BookingEventPublisher(rabbitTemplate, objectMapper);

        publisher.bookingCreated(sampleBooking());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                eq("nomadix.events"), eq("booking.created"), payload.capture());

        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals("booking.created", json.path("type").asText());
        assertEquals(42L, json.path("bookingId").asLong());
        assertEquals(1L, json.path("travelerId").asLong());
        assertEquals(2L, json.path("flightId").asLong());
        assertEquals("CONFIRMED", json.path("status").asText());
        assertEquals(0, new BigDecimal("350.50").compareTo(json.path("totalPrice").decimalValue()));
    }

    @Test
    @DisplayName("booking.cancelled utilise une routing key distincte")
    void bookingCancelledIsPublished() {
        BookingEventPublisher publisher = new BookingEventPublisher(rabbitTemplate, objectMapper);
        Booking booking = sampleBooking();
        booking.setStatus("CANCELLED");

        publisher.bookingCancelled(booking);

        verify(rabbitTemplate).convertAndSend(
                eq("nomadix.events"), eq("booking.cancelled"), anyString());
    }

    @Test
    @DisplayName("RabbitMQ indisponible : la reservation ne doit jamais echouer")
    void rabbitFailureDoesNotBreakBusinessFlow() {
        doThrow(new AmqpException("RabbitMQ down"))
                .when(rabbitTemplate)
                .convertAndSend(eq("nomadix.events"), eq("booking.created"), anyString());

        BookingEventPublisher publisher = new BookingEventPublisher(rabbitTemplate, objectMapper);

        assertDoesNotThrow(() -> publisher.bookingCreated(sampleBooking()));
    }
}
