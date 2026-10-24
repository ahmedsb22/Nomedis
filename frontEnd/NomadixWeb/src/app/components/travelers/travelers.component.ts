import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-travelers',
  standalone: true,
  imports: [CommonModule],
  template: '<div style="padding:20px;font-family:Arial;max-width:950px;margin:auto;"><h2 style="color:#1a237e;">Voyageurs (reparee v17)</h2><div style="margin-top:10px;padding:12px;background:#e3f2fd;border:2px solid #1976d2;border-radius:8px;font-size:13px;color:#0d47a1;"><b>Diagnostic v17</b> | URL : /api/travelers | Etat : <b>{{ state() }}</b> | Elements : {{ items().length }} | Temps : {{ secs() }} s<div *ngIf="error()" style="color:#b71c1c;margin-top:8px;font-weight:bold;">{{ error() }}</div><div *ngIf="extra()" style="margin-top:8px;word-break:break-all;background:#bbdefb;padding:6px;border-radius:4px;">{{ extra() }}</div></div><div *ngIf="loading()" style="margin-top:15px;color:#555;font-size:16px;">Chargement des donnees...</div><div *ngIf="!loading() && !error() && items().length === 0" style="margin-top:15px;color:#e65100;">Le service a repondu une liste vide (0 element) : la base est peut-etre vide.</div><table *ngIf="!loading() && items().length > 0" style="width:100%;border-collapse:collapse;margin-top:15px;"><tr style="background:#e8eaf6;"><th style="padding:8px;border:1px solid #ddd;">ID</th><th style="padding:8px;border:1px solid #ddd;">Apercu</th></tr><tr *ngFor="let it of items(); let i = index"><td style="padding:8px;border:1px solid #ddd;">{{ it.id || it._id }}</td><td style="padding:8px;border:1px solid #ddd;font-size:12px;">{{ apercu(i) }}</td></tr></table></div>'
})
export class TravelersComponent implements OnInit {
  items = signal<any[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);
  state = signal('demarrage');
  secs = signal(0);
  extra = signal('');
  t0 = Date.now();

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.state.set('requete envoyee');
    this.http.get<any[]>('/api/travelers').subscribe({
      next: (d) => {
        this.items.set(d || []);
        this.loading.set(false);
        const n = d ? d.length : 0;
        if (n === 0) { this.state.set('OK - liste vide'); } else { this.state.set('OK'); }
        this.extra.set('Reponse brute : ' + JSON.stringify(d).substring(0, 200));
      },
      error: (e) => {
        this.loading.set(false);
        this.state.set('ERREUR HTTP');
        this.error.set('Echec de la requete : HTTP ' + e.status + ' ' + e.statusText);
        this.extra.set('Si HTTP 404 : mauvais chemin. Si HTTP 0 : service arrete ou proxy absent.');
      }
    });
    setInterval(() => {
      if (!this.loading()) { return; }
      this.secs.set(Math.round((Date.now() - this.t0) / 1000));
    }, 1000);
    setTimeout(() => {
      if (this.loading()) {
        this.loading.set(false);
        this.state.set('TIMEOUT 8 s');
        this.error.set('Aucune reponse apres 8 secondes : verifie que le microservice tourne.');
      }
    }, 8000);
  }

  apercu(i: number): string {
    const it = this.items()[i];
    if (!it) { return ''; }
    const s = JSON.stringify(it);
    if (s.length > 120) { return s.substring(0, 120) + '...'; }
    return s;
  }
}
