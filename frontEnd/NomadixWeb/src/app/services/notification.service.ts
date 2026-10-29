import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import {
  ConsumedEvent,
  EmittedEvent,
  Notification,
  NotificationPayload,
} from '../models/nomadix.models';

/** Notifications (service Python / FastAPI) et emission d'evenements RabbitMQ. */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/notifications';

  getNotifications(): Observable<Notification[]> {
    return this.http.get<Notification[]>(this.apiUrl);
  }

  sendNotification(payload: NotificationPayload): Observable<Notification> {
    return this.http.post<Notification>(this.apiUrl, payload);
  }

  deleteNotification(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  /**
   * Historique des evenements consommes par packh_rabbit_consumer.py
   * (fichier notifications.json partage avec l'API via un volume Docker).
   */
  getConsumedEvents(): Observable<ConsumedEvent[]> {
    return this.http.get<ConsumedEvent[]>(`${this.apiUrl}/events`);
  }

  /**
   * Publie un evenement dans l'exchange topic `nomadix.events`.
   * Le consommateur Python (`packh_rabbit_consumer.py`) le stocke ensuite
   * dans notifications.json.
   */
  emitEvent(service: 'flight' | 'traveler' | 'booking'): Observable<EmittedEvent> {
    return this.http.post<EmittedEvent>(`/api/packh/${service}-events/emit`, {});
  }
}
