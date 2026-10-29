import { Routes } from '@angular/router';
import { FlightsComponent } from './components/flights/flights.component';
import { TravelersComponent } from './components/travelers/travelers.component';
import { BookingsComponent } from './components/bookings/bookings.component';
import { ConferencesComponent } from './components/conferences/conferences.component';
import { NotificationsComponent } from './components/notifications/notifications.component';
import { authGuard } from './auth/auth.guard';

/**
 * Les lectures publiques (vols, conferences) restent accessibles sans compte.
 * Les donnees personnelles (voyageurs, reservations, notifications) sont
 * protegees par Keycloak via authGuard.
 */
export const routes: Routes = [
  { path: '', redirectTo: '/flights', pathMatch: 'full' },
  { path: 'flights', component: FlightsComponent },
  { path: 'travelers', component: TravelersComponent, canActivate: [authGuard] },
  { path: 'bookings', component: BookingsComponent, canActivate: [authGuard] },
  { path: 'conferences', component: ConferencesComponent },
  { path: 'notifications', component: NotificationsComponent, canActivate: [authGuard] }
];
