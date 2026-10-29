import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import {
  ConsumedEvent,
  Notification,
  NotificationType,
} from '../../models/nomadix.models';
import { NotificationService } from '../../services/notification.service';
import { KeycloakService } from '../../auth/keycloak.service';
import { httpErrorMessage } from '../../core/http-error';

type EventSource = 'flight' | 'traveler' | 'booking';

/**
 * Notifications (FastAPI) et evenements RabbitMQ.
 *
 * - GET/POST/DELETE /api/notifications : stockage memoire du service Python ;
 * - GET /api/notifications/events : archive ecrite par le consommateur
 *   RabbitMQ (notifications.json), ce qui prouve le canal asynchrone ;
 * - POST /api/packh/*-events/emit (ADMIN) : publie un evenement dans
 *   l'exchange topic `nomadix.events`.
 */
@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './notifications.component.html',
})
export class NotificationsComponent implements OnInit {
  private readonly notificationService = inject(NotificationService);
  protected readonly auth = inject(KeycloakService);

  protected readonly notifications = signal<Notification[]>([]);
  protected readonly events = signal<ConsumedEvent[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected travelerId: number | null = 1;
  protected type: NotificationType = 'booking_confirmed';
  protected message = '';

  protected readonly eventSources: EventSource[] = ['flight', 'traveler', 'booking'];

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.notificationService.getNotifications().subscribe({
      next: (notifications) => {
        this.notifications.set(notifications ?? []);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.loading.set(false);
      },
    });
    // Archive du consommateur RabbitMQ : chargement independant pour ne pas
    // bloquer la liste des notifications si le fichier n'existe pas encore.
    this.notificationService.getConsumedEvents().subscribe({
      next: (events) => this.events.set(events ?? []),
      error: () => this.events.set([]),
    });
  }

  /** Resume lisible du corps d'un evenement consomme. */
  protected eventSummary(event: ConsumedEvent): string {
    const payload = event.payload ?? {};
    const parts = Object.entries(payload)
      .filter(([key]) => !['type', 'occurredAt', 'publishedTo'].includes(key))
      .map(([key, value]) => `${key}=${String(value)}`);
    return parts.length > 0 ? parts.join(', ') : '(corps vide)';
  }

  protected canSend(): boolean {
    return (
      !this.busy() &&
      this.travelerId !== null &&
      this.travelerId > 0 &&
      this.message.trim().length > 0
    );
  }

  protected send(): void {
    if (!this.canSend() || this.travelerId === null) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.notificationService
      .sendNotification({
        traveler_id: this.travelerId,
        type: this.type,
        message: this.message.trim(),
      })
      .subscribe({
        next: (notification) => {
          this.busy.set(false);
          this.notice.set(`Notification #${notification.id} enregistree (statut ${notification.status}).`);
          this.message = '';
          this.load();
        },
        error: (error) => {
          this.error.set(httpErrorMessage(error));
          this.busy.set(false);
        },
      });
  }

  protected remove(notification: Notification): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.notificationService.deleteNotification(notification.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.notice.set(`Notification #${notification.id} supprimee.`);
        this.load();
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.busy.set(false);
      },
    });
  }

  protected emit(source: EventSource): void {
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.notificationService.emitEvent(source).subscribe({
      next: (event) => {
        this.busy.set(false);
        this.notice.set(
          `Evenement ${event.routingKey} publie dans ${event.publishedTo}. ` +
            'Verifiez la file notification.events dans la console RabbitMQ (http://localhost:15672).',
        );
      },
      error: (error) => {
        this.error.set(httpErrorMessage(error));
        this.busy.set(false);
      },
    });
  }

  protected typeLabel(type: string): string {
    if (type === 'flight_delay') {
      return 'Retard de vol';
    }
    if (type === 'payment_success') {
      return 'Paiement reussi';
    }
    return 'Reservation confirmee';
  }
}
