import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { Flight, Traveler } from '../../models/nomadix.models';
import { TravelerService } from '../../services/traveler.service';
import { FlightService } from '../../services/flight.service';
import { httpErrorMessage } from '../../core/http-error';
import { KeycloakService } from '../../auth/keycloak.service';

/**
 * Voyageurs et vols favoris.
 *
 * La liste est protegee par Keycloak (authGuard + jeton ajoute par
 * l'intercepteur). Le panneau « favoris » illustre OpenFeign : le
 * Traveler-Service lit chaque vol sur le Flight-Service avant de repondre.
 */
@Component({
  selector: 'app-travelers',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './travelers.component.html',
})
export class TravelersComponent implements OnInit {
  private readonly travelerService = inject(TravelerService);
  private readonly flightService = inject(FlightService);

  protected readonly travelers = signal<Traveler[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly expandedId = signal<number | null>(null);
  protected readonly favorites = signal<Record<number, Flight[]>>({});
  protected readonly favoritesLoading = signal<number | null>(null);
  protected readonly favoriteError = signal<string | null>(null);
  protected readonly selectedFlightId = signal<number | null>(null);
  protected readonly saving = signal(false);
  protected readonly notice = signal<string | null>(null);

  /** Catalogue utilise par le selecteur « ajouter un favori ». */
  protected readonly flights = signal<Flight[]>([]);

  protected readonly auth = inject(KeycloakService);

  // Formulaire de creation / edition (ngModel). `editingId` vaut null en creation.
  protected firstName = '';
  protected lastName = '';
  protected email = '';
  protected role = 'USER';
  protected readonly editingId = signal<number | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.travelerService.getTravelers().subscribe({
      next: (travelers) => {
        this.travelers.set(travelers ?? []);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.loading.set(false);
      },
    });
  }

  protected initials(traveler: Traveler): string {
    const first = traveler.firstName?.charAt(0) ?? '';
    const last = traveler.lastName?.charAt(0) ?? '';
    return `${first}${last}`.toUpperCase() || '?';
  }

  protected fullName(traveler: Traveler): string {
    return `${traveler.firstName ?? ''} ${traveler.lastName ?? ''}`.trim();
  }

  protected favoritesFor(id: number): Flight[] {
    return this.favorites()[id] ?? [];
  }

  protected toggle(traveler: Traveler): void {
    this.notice.set(null);
    if (this.expandedId() === traveler.id) {
      this.expandedId.set(null);
      return;
    }
    this.expandedId.set(traveler.id);
    this.loadFavorites(traveler.id);
    if (this.flights().length === 0) {
      this.flightService.getFlights().subscribe({
        next: (flights) => this.flights.set(flights ?? []),
        error: () => this.flights.set([]),
      });
    }
  }

  protected loadFavorites(travelerId: number): void {
    this.favoritesLoading.set(travelerId);
    this.favoriteError.set(null);
    this.travelerService.getFavoriteFlights(travelerId).subscribe({
      next: (flights) => {
        this.favorites.update((current) => ({ ...current, [travelerId]: flights ?? [] }));
        this.favoritesLoading.set(null);
      },
      error: (error) => {
        this.favoriteError.set(httpErrorMessage(error));
        this.favoritesLoading.set(null);
      },
    });
  }

  protected addFavorite(traveler: Traveler): void {
    const flightId = this.selectedFlightId();
    if (!flightId) {
      return;
    }
    this.saving.set(true);
    this.favoriteError.set(null);
    this.travelerService.addFavoriteFlight(traveler.id, flightId).subscribe({
      next: () => {
        this.saving.set(false);
        this.notice.set(`Vol ${flightId} ajoute aux favoris de ${this.fullName(traveler)}.`);
        this.selectedFlightId.set(null);
        this.loadFavorites(traveler.id);
        this.load();
      },
      error: (error) => {
        this.favoriteError.set(httpErrorMessage(error));
        this.saving.set(false);
      },
    });
  }

  /** Le formulaire est valide quand les trois champs obligatoires sont remplis. */
  protected canSaveTraveler(): boolean {
    return (
      !this.saving() &&
      this.firstName.trim() !== '' &&
      this.lastName.trim() !== '' &&
      this.email.trim() !== ''
    );
  }

  protected startCreate(): void {
    this.resetForm();
    this.notice.set(null);
  }

  protected startEdit(traveler: Traveler): void {
    this.editingId.set(traveler.id);
    this.firstName = traveler.firstName ?? '';
    this.lastName = traveler.lastName ?? '';
    this.email = traveler.email ?? '';
    this.role = traveler.role || 'USER';
    this.notice.set(null);
    this.expandedId.set(null);
  }

  /** POST (creation) ou PUT (edition) selon l'etat du formulaire. */
  protected saveTraveler(): void {
    if (!this.canSaveTraveler()) {
      return;
    }
    const payload = {
      firstName: this.firstName.trim(),
      lastName: this.lastName.trim(),
      email: this.email.trim(),
      role: this.role || 'USER',
    };
    const id = this.editingId();
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    const request = id === null
      ? this.travelerService.createTraveler(payload)
      : this.travelerService.updateTraveler(id, payload);
    request.subscribe({
      next: (traveler) => {
        this.saving.set(false);
        this.notice.set(
          id === null
            ? `Voyageur ${traveler.firstName} ${traveler.lastName} cree (#${traveler.id}).`
            : `Voyageur ${traveler.firstName} ${traveler.lastName} mis a jour.`,
        );
        this.resetForm();
        this.load();
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.saving.set(false);
      },
    });
  }

  /** Suppression : le service exige le role ADMIN (403 sinon). */
  protected removeTraveler(traveler: Traveler): void {
    const label = `${traveler.firstName} ${traveler.lastName}`;
    if (!window.confirm(`Supprimer le voyageur ${label} ?`)) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.travelerService.deleteTraveler(traveler.id).subscribe({
      next: () => {
        this.saving.set(false);
        this.notice.set(`Voyageur ${label} supprime.`);
        if (this.editingId() === traveler.id) {
          this.resetForm();
        }
        this.load();
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.saving.set(false);
      },
    });
  }

  private resetForm(): void {
    this.editingId.set(null);
    this.firstName = '';
    this.lastName = '';
    this.email = '';
    this.role = 'USER';
  }

  protected removeFavorite(traveler: Traveler, flight: Flight): void {
    this.saving.set(true);
    this.favoriteError.set(null);
    this.travelerService.removeFavoriteFlight(traveler.id, flight.id).subscribe({
      next: () => {
        this.saving.set(false);
        this.notice.set(`Vol ${flight.flightNumber} retire des favoris.`);
        this.loadFavorites(traveler.id);
        this.load();
      },
      error: (error) => {
        this.favoriteError.set(httpErrorMessage(error));
        this.saving.set(false);
      },
    });
  }
}
