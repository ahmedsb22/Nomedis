import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { Flight, FlightInput } from '../models/nomadix.models';

/**
 * Catalogue des vols.
 *
 * Les URL sont relatives (`/api/...`) : en developpement, le proxy Angular
 * (`proxy.conf.json`) les renvoie vers la Gateway sur le port 8089, ce qui
 * evite tout probleme CORS dans le navigateur.
 */
@Injectable({ providedIn: 'root' })
export class FlightService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/flights';

  getFlights(): Observable<Flight[]> {
    return this.http.get<Flight[]>(this.apiUrl);
  }

  getFlight(id: number): Observable<Flight> {
    return this.http.get<Flight>(`${this.apiUrl}/${id}`);
  }

  /** Ajout au catalogue : POST /api/flights (role ADMIN exige par la Gateway). */
  createFlight(flight: FlightInput): Observable<Flight> {
    return this.http.post<Flight>(this.apiUrl, flight);
  }
}
