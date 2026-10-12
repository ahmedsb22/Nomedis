import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class FlightService {
  // URL de la API Gateway
  private apiUrl = 'http://localhost:8089/api/flights';

  constructor(private http: HttpClient) { }

  getFlights(): Observable<any[]> {
    return this.http.get<any[]>(this.apiUrl);
  }
}
