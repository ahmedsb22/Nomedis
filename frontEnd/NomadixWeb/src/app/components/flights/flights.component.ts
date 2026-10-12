import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-flights',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div style="padding: 20px; font-family: Arial;">
      <h2>✈️ Liste des Vols Disponibles</h2>
      
      <div *ngIf="loading">Chargement des vols...</div>
      <div *ngIf="error" style="color: red;">Erreur: {{ error }}</div>
      
      <table *ngIf="!loading && !error && flights.length > 0" style="width: 100%; border-collapse: collapse; margin-top: 15px;">
        <tr style="background-color: #f2f2f2;">
          <th style="padding: 10px; border: 1px solid #ddd;">Départ</th>
          <th style="padding: 10px; border: 1px solid #ddd;">Arrivée</th>
          <th style="padding: 10px; border: 1px solid #ddd;">Prix</th>
          <th style="padding: 10px; border: 1px solid #ddd;">Sièges</th>
        </tr>
        <tr *ngFor="let flight of flights">
          <td style="padding: 10px; border: 1px solid #ddd;">{{ flight.origin }}</td>
          <td style="padding: 10px; border: 1px solid #ddd;">{{ flight.destination }}</td>
          <td style="padding: 10px; border: 1px solid #ddd;">{{ flight.price }} €</td>
          <td style="padding: 10px; border: 1px solid #ddd;">{{ flight.availableSeats }}</td>
        </tr>
      </table>
      
      <div *ngIf="!loading && !error && flights.length === 0" style="margin-top: 15px;">
        Aucun vol trouvé pour le moment.
      </div>
    </div>
  `
})
export class FlightsComponent implements OnInit {
  flights: any[] = [];
  loading = true;
  error: string | null = null;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    // Appel direct à la Gateway qui redirigera vers le microservice Flight
    this.http.get<any[]>('http://localhost:8089/api/flights').subscribe({
      next: (data) => {
        this.flights = data;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Impossible de charger les vols. Vérifiez que la Gateway (port 8089) et les services sont actifs.';
        this.loading = false;
        console.error('Erreur API:', err);
      }
    });
  }
}