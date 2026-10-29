import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { Conference, Flight } from '../../models/nomadix.models';
import { ConferenceService } from '../../services/conference.service';
import { FlightService } from '../../services/flight.service';
import { KeycloakService } from '../../auth/keycloak.service';
import { httpErrorMessage } from '../../core/http-error';

/**
 * Conferences (service Node/Express).
 *
 * Lecture publique ; la creation et la suppression sont reservees au role
 * ADMIN, regle appliquee deux fois : par la Gateway et par le bouton masque ici.
 */
@Component({
  selector: 'app-conferences',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './conferences.component.html',
})
export class ConferencesComponent implements OnInit {
  private readonly conferenceService = inject(ConferenceService);
  private readonly flightService = inject(FlightService);
  protected readonly auth = inject(KeycloakService);

  protected readonly conferences = signal<Conference[]>([]);
  protected readonly flights = signal<Flight[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly saving = signal(false);

  // Champs du formulaire (ngModel).
  protected title = '';
  protected location = '';
  protected date = '';
  protected flightId: number | null = null;

  ngOnInit(): void {
    this.load();
    this.flightService.getFlights().subscribe({
      next: (flights) => this.flights.set(flights ?? []),
      error: () => this.flights.set([]),
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.conferenceService.getConferences().subscribe({
      next: (conferences) => {
        this.conferences.set(conferences ?? []);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.loading.set(false);
      },
    });
  }

  protected flightLabel(flightId: number | null): string | null {
    if (flightId === null) {
      return null;
    }
    const flight = this.flights().find((item) => item.id === flightId);
    return flight
      ? `${flight.flightNumber} (${flight.departureCity} -> ${flight.arrivalCity})`
      : `Vol #${flightId}`;
  }

  protected canSubmit(): boolean {
    return !this.saving() && this.title.trim() !== '' && this.location.trim() !== '' && this.date !== '';
  }

  protected create(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.conferenceService
      .createConference({
        title: this.title.trim(),
        location: this.location.trim(),
        date: this.date,
        flightId: this.flightId,
      })
      .subscribe({
        next: (conference) => {
          this.saving.set(false);
          this.notice.set(`Conference « ${conference.title} » enregistree.`);
          this.title = '';
          this.location = '';
          this.date = '';
          this.flightId = null;
          this.load();
        },
        error: (error) => {
          this.error.set(httpErrorMessage(error));
          this.saving.set(false);
        },
      });
  }

  protected remove(conference: Conference): void {
    this.saving.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.conferenceService.deleteConference(conference.id).subscribe({
      next: () => {
        this.saving.set(false);
        this.notice.set(`Conference « ${conference.title} » supprimee.`);
        this.load();
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.saving.set(false);
      },
    });
  }
}
