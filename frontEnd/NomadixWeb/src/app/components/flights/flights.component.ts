import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { Flight } from '../../models/nomadix.models';
import { FlightService } from '../../services/flight.service';
import { KeycloakService } from '../../auth/keycloak.service';
import { httpErrorMessage } from '../../core/http-error';

/**
 * Catalogue des vols (lecture publique).
 *
 * Les donnees viennent de GET /api/flights via la Gateway : aucun jeton n'est
 * necessaire pour consulter. Le bouton « Reserver » transmet l'identifiant du
 * vol a la page Reservations (query param flightId).
 */
@Component({
  selector: 'app-flights',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './flights.component.html',
})
export class FlightsComponent implements OnInit {
  private readonly flightService = inject(FlightService);
  private readonly router = inject(Router);
  protected readonly auth = inject(KeycloakService);

  protected readonly flights = signal<Flight[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly onlyAvailable = signal(false);

  // Formulaire d'ajout au catalogue, visible uniquement pour le role ADMIN.
  protected readonly saving = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected flightNumber = '';
  protected departureCity = '';
  protected arrivalCity = '';
  protected departureDate = '';
  protected availableSeats: number | null = 120;
  protected price: number | null = null;

  /** Filtre local : numero de vol, ville de depart ou d'arrivee. */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    return this.flights().filter((flight) => {
      if (this.onlyAvailable() && flight.availableSeats <= 0) {
        return false;
      }
      if (!term) {
        return true;
      }
      return [flight.flightNumber, flight.departureCity, flight.arrivalCity].some((value) =>
        (value ?? '').toLowerCase().includes(term),
      );
    });
  });

  protected readonly availableCount = computed(
    () => this.flights().filter((flight) => flight.availableSeats > 0).length,
  );

  protected readonly destinationCount = computed(
    () => new Set(this.flights().map((flight) => flight.arrivalCity)).size,
  );

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.flightService.getFlights().subscribe({
      next: (flights) => {
        this.flights.set(flights ?? []);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.loading.set(false);
      },
    });
  }

  protected onSearch(value: string): void {
    this.search.set(value);
  }

  protected canCreateFlight(): boolean {
    return (
      !this.saving() &&
      this.flightNumber.trim() !== '' &&
      this.departureCity.trim() !== '' &&
      this.arrivalCity.trim() !== '' &&
      this.departureDate !== '' &&
      this.availableSeats !== null &&
      this.availableSeats >= 0
    );
  }

  /** POST /api/flights : la Gateway exige le role ADMIN (403 pour un TRAVELER). */
  protected createFlight(): void {
    if (!this.canCreateFlight() || this.availableSeats === null) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.flightService
      .createFlight({
        flightNumber: this.flightNumber.trim().toUpperCase(),
        departureCity: this.departureCity.trim(),
        arrivalCity: this.arrivalCity.trim(),
        departureDate: this.departureDate,
        availableSeats: this.availableSeats,
        price: this.price,
      })
      .subscribe({
        next: (flight) => {
          this.saving.set(false);
          this.notice.set(`Vol ${flight.flightNumber} ajoute au catalogue (#${flight.id}).`);
          this.flightNumber = '';
          this.departureCity = '';
          this.arrivalCity = '';
          this.departureDate = '';
          this.availableSeats = 120;
          this.price = null;
          this.load();
        },
        error: (error) => {
          this.error.set(httpErrorMessage(error));
          this.saving.set(false);
        },
      });
  }

  protected seatsBadge(seats: number): string {
    if (seats <= 0) {
      return 'badge-red';
    }
    return seats <= 20 ? 'badge-amber' : 'badge-green';
  }

  protected seatsLabel(seats: number): string {
    return seats <= 0 ? 'Complet' : `${seats} places`;
  }

  /** Ouvre la page Reservations avec le vol preselectionne (authGuard si besoin). */
  protected book(flight: Flight): void {
    void this.router.navigate(['/bookings'], { queryParams: { flightId: flight.id } });
  }
}
