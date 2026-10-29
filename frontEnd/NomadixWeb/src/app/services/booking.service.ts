import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

import { Booking, BookingStatus } from '../models/nomadix.models';

/**
 * Reservations.
 *
 * La creation declenche la mini-saga du Booking-Service :
 * validation du voyageur (Feign) -> controle du stock (Feign) -> decrement
 * atomique -> ecriture locale -> evenement RabbitMQ `booking.created`.
 */
@Injectable({ providedIn: 'root' })
export class BookingService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/bookings';

  getBookings(): Observable<Booking[]> {
    return this.http.get<Booking[]>(this.apiUrl);
  }

  createBooking(travelerId: number, flightId: number): Observable<Booking> {
    const params = new HttpParams()
      .set('travelerId', travelerId)
      .set('flightId', flightId);
    return this.http.post<Booking>(this.apiUrl, null, { params });
  }

  updateStatus(id: number, status: BookingStatus): Observable<Booking> {
    const params = new HttpParams().set('status', status);
    return this.http.put<Booking>(`${this.apiUrl}/${id}`, null, { params });
  }

  cancelBooking(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
