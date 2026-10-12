package com.nomadix.booking.controller;

import com.nomadix.booking.entity.Booking;
import com.nomadix.booking.repository.BookingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints de consultation des reservations (GET uniquement).
 * Resout le 405 Method Not Allowed sur GET /api/bookings en complement
 * du BookingController existant (POST).
 */
@RestController
@RequestMapping("/api/bookings")
@Tag(name = "Bookings (consultation)", description = "Consultation des reservations Nomadix")
public class BookingQueryController {

    private final BookingRepository bookingRepository;

    public BookingQueryController(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Operation(summary = "Lister toutes les reservations")
    @GetMapping
    public List<Booking> getAll() {
        return bookingRepository.findAll();
    }

    @Operation(summary = "Obtenir une reservation par son id")
    @GetMapping("/{id}")
    public ResponseEntity<Booking> getById(@PathVariable Long id) {
        return bookingRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}