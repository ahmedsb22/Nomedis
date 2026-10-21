import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { timeout } from 'rxjs/operators';

@Component({
  selector: 'app-flights',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div style="padding:20px;font-family:Arial;">
      <h2>Liste des Vols Disponibles</h2>
      <div *ngIf="loading">Chargement...</div>
      <div *ngIf="error" style="color:#b71c1c;background:#ffebee;padding:10px;border-radius:6px;margin-top:10px;">{{ error }}</div>
      <table *ngIf="!loading && !error && items.length > 0" style="width:100%;border-collapse:collapse;margin-top:15px;">
        <tr style="background:#e8eaf6;"><th style="padding:10px;border:1px solid #ddd;">Numero</th><th style="padding:10px;border:1px solid #ddd;">Depart</th><th style="padding:10px;border:1px solid #ddd;">Arrivee</th><th style="padding:10px;border:1px solid #ddd;">Date</th><th style="padding:10px;border:1px solid #ddd;">Sieges</th></tr>
        <tr *ngFor="let f of items"><td style="padding:10px;border:1px solid #ddd;">{{ f.flightNumber }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.departureCity }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.arrivalCity }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.departureDate }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.availableSeats }}</td></tr>
      </table>
      <div *ngIf="!loading && !error && items.length === 0" style="margin-top:15px;">Aucune donnee (base vide ou service non demarre).</div>
    </div>
  `
})
export class FlightsComponent implements OnInit {
  items: any[] = [];
  loading = true;
  error: string | null = null;
  constructor(private http: HttpClient) {}
  ngOnInit(): void {
    this.http.get<any[]>('/api/flights').pipe(timeout(8000)).subscribe({
      next: (d) => { this.items = d || []; this.loading = false; },
      error: (e) => { this.error = 'Impossible de charger les vols (service arrete ou timeout). Verifie que flight-service tourne.' + (e && e.message ? ' (' + e.message + ')' : ''); this.loading = false; }
    });
  }
}
