import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { Conference } from '../models/nomadix.models';

/** Conferences (service Node/Express, lecture publique, ecriture ADMIN). */
@Injectable({ providedIn: 'root' })
export class ConferenceService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = '/api/conferences';

  getConferences(): Observable<Conference[]> {
    return this.http.get<Conference[]>(this.apiUrl);
  }

  createConference(conference: Omit<Conference, 'id'>): Observable<Conference> {
    return this.http.post<Conference>(this.apiUrl, conference);
  }

  deleteConference(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
