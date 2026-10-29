package com.nomadix.booking.service;

import com.nomadix.booking.client.FlightClient;
import com.nomadix.booking.client.TravelerClient;
import com.nomadix.booking.dto.FlightDTO;
import com.nomadix.booking.dto.TravelerDTO;
import com.nomadix.booking.entity.Booking;
import com.nomadix.booking.messaging.BookingEventPublisher;
import com.nomadix.booking.repository.BookingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final String AUTHORIZATION = "Bearer test-token";

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private FlightClient flightClient;

    @Mock
    private TravelerClient travelerClient;

    @Mock
    private BookingEventPublisher eventPublisher;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, flightClient, travelerClient, eventPublisher);

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("sami")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Creation : Traveler valide, stock decremente, prix Feign et booking.created publie")
    void createBookingOrchestratesFeignAndPublishesEvent() {
        TravelerDTO traveler = new TravelerDTO();
        traveler.setId(1L);
        FlightDTO flight = new FlightDTO();
        flight.setId(2L);
        flight.setAvailableSeats(3);
        flight.setPrice(new BigDecimal("350.50"));

        when(travelerClient.getTravelerById(1L, AUTHORIZATION)).thenReturn(traveler);
        when(flightClient.getFlightById(2L, AUTHORIZATION)).thenReturn(flight);
        when(flightClient.reserveSeat(2L, AUTHORIZATION)).thenReturn(flight);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setId(99L);
            return booking;
        });

        Booking result = service.createBooking(1L, 2L);

        assertEquals(99L, result.getId());
        assertEquals("CONFIRMED", result.getStatus());
        assertEquals(new BigDecimal("350.50"), result.getTotalPrice());
        verify(flightClient).reserveSeat(2L, AUTHORIZATION);
        verify(eventPublisher).bookingCreated(result);
    }

    @Test
    @DisplayName("Annulation : statut CANCELLED, place restituee et booking.cancelled publie")
    void cancellationReleasesSeatAndPublishesEvent() {
        Booking booking = new Booking();
        booking.setId(5L);
        booking.setFlightId(2L);
        booking.setStatus("CONFIRMED");

        when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.cancelBooking(5L);

        assertEquals("CANCELLED", booking.getStatus());
        verify(flightClient).releaseSeat(2L, AUTHORIZATION);
        verify(eventPublisher).bookingCancelled(booking);
    }

    @Test
    @DisplayName("Annulation idempotente : aucune place ni evenement une seconde fois")
    void cancellationIsIdempotent() {
        Booking booking = new Booking();
        booking.setId(6L);
        booking.setFlightId(2L);
        booking.setStatus("CANCELLED");

        when(bookingRepository.findById(6L)).thenReturn(Optional.of(booking));

        service.cancelBooking(6L);

        verify(bookingRepository, never()).save(any(Booking.class));
        verifyNoInteractions(flightClient, eventPublisher);
    }
}
