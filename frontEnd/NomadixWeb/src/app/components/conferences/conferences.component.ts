import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { timeout } from 'rxjs/operators';

@Component({
  selector: 'app-conferences',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div style="padding:20px;font-family:Arial;">
      <h2>Conferences (service Node.js)</h2>
      <div *ngIf="loading">Chargement...</div>
      <div *ngIf="error" style="color:#b71c1c;background:#ffebee;padding:10px;border-radius:6px;margin-top:10px;">{{ error }}</div>
      <table *ngIf="!loading && !error && items.length > 0" style="width:100%;border-collapse:collapse;margin-top:15px;">
        <tr style="background:#e8eaf6;"><th style="padding:10px;border:1px solid #ddd;">Id</th><th style="padding:10px;border:1px solid #ddd;">Titre</th><th style="padding:10px;border:1px solid #ddd;">Details</th></tr>
        <tr *ngFor="let f of items"><td style="padding:10px;border:1px solid #ddd;">{{ f.id }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.title || f.name }}</td><td style="padding:10px;border:1px solid #ddd;">{{ f.description || f.location || f.place }}</td></tr>
      </table>
      <div *ngIf="!loading && !error && items.length === 0" style="margin-top:15px;">Aucune donnee (base vide ou service non demarre).</div>
    </div>
  `
})
export class ConferencesComponent implements OnInit {
  items: any[] = [];
  loading = true;
  error: string | null = null;
  constructor(private http: HttpClient) {}
  ngOnInit(): void {
    this.http.get<any[]>('/api/conferences').pipe(timeout(8000)).subscribe({
      next: (d) => { this.items = d || []; this.loading = false; },
      error: (e) => { this.error = 'Conference service (Node, port 3000) non demarre.' + (e && e.message ? ' (' + e.message + ')' : ''); this.loading = false; }
    });
  }
}
