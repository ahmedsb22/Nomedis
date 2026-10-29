import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { Flight, Traveler, TravelerInput } from '../models/nomadix.models';

/**
 * Voyageurs et vols favoris.
 *
 * Les favoris sont l'illustration d'OpenFeign : le Traveler-Service ne stocke
 * que des identifiants et appelle le Flight-Service en synchrone pour renvoyer
 * les vols complets.
 */
@Injectable({ providedIn: 'root' })
export class TravelerService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/travelers';

  getTravelers(): Observable<Traveler[]> {
    return this.http.get<Traveler[]>(this.apiUrl);
  }

  getTraveler(id: number): Observable<Traveler> {
    return this.http.get<Traveler>(`${this.apiUrl}/${id}`);
  }

  /** Creation : POST /api/travelers (jeton requis, id genere cote service). */
  createTraveler(traveler: TravelerInput): Observable<Traveler> {
    return this.http.post<Traveler>(this.apiUrl, traveler);
  }

  /** Mise a jour : PUT /api/travelers/{id} (jeton requis). */
  updateTraveler(id: number, traveler: TravelerInput): Observable<Traveler> {
    return this.http.put<Traveler>(`${this.apiUrl}/${id}`, traveler);
  }

  /** Suppression : DELETE /api/travelers/{id} (role ADMIN exige par le service). */
  deleteTraveler(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  getFavoriteFlights(travelerId: number): Observable<Flight[]> {
    return this.http.get<Flight[]>(`${this.apiUrl}/${travelerId}/favorite-flights`);
  }

  addFavoriteFlight(travelerId: number, flightId: number): Observable<string> {
    return this.http.post(
      `${this.apiUrl}/${travelerId}/favorite-flights/${flightId}`,
      null,
      { responseType: 'text' },
    );
  }

  removeFavoriteFlight(travelerId: number, flightId: number): Observable<string> {
    return this.http.delete(`${this.apiUrl}/${travelerId}/favorite-flights/${flightId}`, {
      responseType: 'text',
    });
  }
}
