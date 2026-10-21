import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { timeout } from 'rxjs/operators';

@Component({
  selector: 'app-bookings',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div style="padding:20px;font-family:Arial;">
      <h2>Liste des Reservations</h2>
      <div *ngIf="loading">Chargement...</div>
      <div *ngIf="error" style="color:#b71c1c;background:#ffebee;padding:10px;border-radius:6px;margin-top:10px;">{{ error }}</div>
      <table *ngIf="!loading && !error && items.length > 0" style="width:100%;border-collapse:collapse;margin-top:15px;">
        <tr style="background:#e8eaf6;"><th style="padding:10px;border:1px solid #ddd;">Id</th><th style="padding:10px;border:1px solid #ddd;">Voyageur</th><th style="padding:10px;border:1px solid #ddd;">Vol</th><th style="padding:10px;border:1px solid #ddd;">Statut</th><th style="padding:10px;border:1px solid #ddd;">Prix total</th><th style="padding:10px;border:1px solid #ddd;">Cree le</th></tr>
        <tr *ngFor="let f of items"><td style="padding:10px;border:1px solid #ddd;">{{ f.id }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.travelerId }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.flightId }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.status }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.totalPrice }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.createdAt }}</td></tr>
      </table>
      <div *ngIf="!loading && !error && items.length === 0" style="margin-top:15px;">Aucune donnee (base vide ou service non demarre).</div>
    </div>
  `
})
export class BookingsComponent implements OnInit {
  items: any[] = [];
  loading = true;
  error: string | null = null;
  constructor(private http: HttpClient) {}
  ngOnInit(): void {
    this.http.get<any[]>('/api/bookings').pipe(timeout(8000)).subscribe({
      next: (d) => { this.items = d || []; this.loading = false; },
      error: (e) => { this.error = 'Impossible de charger les reservations. Verifie que booking-service (8083) tourne.' + (e && e.message ? ' (' + e.message + ')' : ''); this.loading = false; }
    });
  }
}
