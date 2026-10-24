package com.nomadix.booking.controller;
import com.nomadix.booking.entity.Booking;
import com.nomadix.booking.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService bookingService;
    public BookingController(BookingService bookingService) { this.bookingService = bookingService; }

    @PostMapping
    public ResponseEntity<Booking> createBooking(@RequestParam Long travelerId, @RequestParam Long flightId) {
        Booking booking = bookingService.createBooking(travelerId, flightId);
        return ResponseEntity.status(HttpStatus.CREATED).body(booking);
    }

    /** Change le statut (PENDING / CONFIRMED / CANCELLED) avec compensation du stock. */
    @PutMapping("/{id}")
    public ResponseEntity<Booking> updateStatus(@PathVariable Long id, @RequestParam String status) {
        return ResponseEntity.ok(bookingService.updateStatus(id, status));
    }

    /** Annule la reservation et restitue la place (idempotent). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelBooking(@PathVariable Long id) {
        bookingService.cancelBooking(id);
        return ResponseEntity.noContent().build();
    }
}
