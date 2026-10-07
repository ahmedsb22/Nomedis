package com.nomadix.booking.controller;
import com.nomadix.booking.entity.Booking;
import com.nomadix.booking.service.BookingService;
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
        return ResponseEntity.ok(booking);
    }
}