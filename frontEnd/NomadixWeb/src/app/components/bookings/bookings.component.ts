import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';

import { Booking, BookingStatus, Flight, Traveler } from '../../models/nomadix.models';
import { BookingService } from '../../services/booking.service';
import { FlightService } from '../../services/flight.service';
import { TravelerService } from '../../services/traveler.service';
import { httpErrorMessage } from '../../core/http-error';

/**
 * Reservations : creation (mini-saga OpenFeign), changement de statut,
 * annulation. Chaque action renvoie ensuite au backend pour reafficher
 * l'etat reel, jamais une valeur supposee par le navigateur.
 */
@Component({
  selector: 'app-bookings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './bookings.component.html',
})
export class BookingsComponent implements OnInit {
  private readonly bookingService = inject(BookingService);
  private readonly travelerService = inject(TravelerService);
  private readonly flightService = inject(FlightService);
  private readonly route = inject(ActivatedRoute);

  protected readonly bookings = signal<Booking[]>([]);
  protected readonly travelers = signal<Traveler[]>([]);
  protected readonly flights = signal<Flight[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly selectedTravelerId = signal<number | null>(null);
  protected readonly selectedFlightId = signal<number | null>(null);

  protected readonly confirmedCount = computed(
    () => this.bookings().filter((booking) => booking.status === 'CONFIRMED').length,
  );
  protected readonly cancelledCount = computed(
    () => this.bookings().filter((booking) => booking.status === 'CANCELLED').length,
  );

  ngOnInit(): void {
    const requestedFlight = Number(this.route.snapshot.queryParamMap.get('flightId'));
    if (Number.isFinite(requestedFlight) && requestedFlight > 0) {
      this.selectedFlightId.set(requestedFlight);
    }
    this.loadAll();
  }

  protected loadAll(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      bookings: this.bookingService.getBookings(),
      travelers: this.travelerService.getTravelers(),
      flights: this.flightService.getFlights(),
    }).subscribe({
      next: ({ bookings, travelers, flights }) => {
        this.bookings.set(bookings ?? []);
        this.travelers.set(travelers ?? []);
        this.flights.set(flights ?? []);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.loading.set(false);
      },
    });
  }

  /** Apres une ecriture : on ne recharge que ce qui change (reservations + stock). */
  private refreshBookings(): void {
    this.bookingService.getBookings().subscribe({
      next: (bookings) => this.bookings.set(bookings ?? []),
      error: (error) => this.error.set(httpErrorMessage(error)),
    });
    this.flightService.getFlights().subscribe({
      next: (flights) => this.flights.set(flights ?? []),
      error: () => undefined,
    });
  }

  protected travelerName(travelerId: number): string {
    const traveler = this.travelers().find((item) => item.id === travelerId);
    return traveler ? `${traveler.firstName} ${traveler.lastName}` : `Voyageur #${travelerId}`;
  }

  protected flightLabel(flightId: number): string {
    const flight = this.flights().find((item) => item.id === flightId);
    return flight
      ? `${flight.flightNumber} - ${flight.departureCity} -> ${flight.arrivalCity}`
      : `Vol #${flightId}`;
  }

  protected statusBadge(status: BookingStatus): string {
    if (status === 'CONFIRMED') {
      return 'badge-green';
    }
    if (status === 'CANCELLED') {
      return 'badge-red';
    }
    return 'badge-amber';
  }

  protected statusLabel(status: BookingStatus): string {
    if (status === 'CONFIRMED') {
      return 'Confirmee';
    }
    if (status === 'CANCELLED') {
      return 'Annulee';
    }
    return 'En attente';
  }

  protected selectedFlight(): Flight | undefined {
    const id = this.selectedFlightId();
    return id === null ? undefined : this.flights().find((flight) => flight.id === id);
  }

  protected createBooking(): void {
    const travelerId = this.selectedTravelerId();
    const flightId = this.selectedFlightId();
    if (travelerId === null || flightId === null) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.bookingService.createBooking(travelerId, flightId).subscribe({
      next: (booking) => {
        this.busy.set(false);
        this.notice.set(
          `Reservation #${booking.id} creee (${this.travelerName(booking.travelerId)}, ` +
            `${this.flightLabel(booking.flightId)}). Une place a ete decrementee par OpenFeign.`,
        );
        this.refreshBookings();
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.busy.set(false);
      },
    });
  }

  protected confirm(booking: Booking): void {
    this.changeStatus(booking, 'CONFIRMED');
  }

  protected changeStatus(booking: Booking, status: BookingStatus): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.bookingService.updateStatus(booking.id, status).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.notice.set(`Reservation #${updated.id} : ${this.statusLabel(updated.status)}.`);
        this.refreshBookings();
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.busy.set(false);
      },
    });
  }

  protected cancel(booking: Booking): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.bookingService.cancelBooking(booking.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.notice.set(
          `Reservation #${booking.id} annulee : la place a ete restituee au vol et ` +
            "l'evenement booking.cancelled est publie dans RabbitMQ.",
        );
        this.refreshBookings();
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.busy.set(false);
      },
    });
  }
}
