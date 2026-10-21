import { Routes } from '@angular/router';
import { FlightsComponent } from './components/flights/flights.component';
import { TravelersComponent } from './components/travelers/travelers.component';
import { BookingsComponent } from './components/bookings/bookings.component';
import { ConferencesComponent } from './components/conferences/conferences.component';
import { NotificationsComponent } from './components/notifications/notifications.component';

export const routes: Routes = [
  { path: '', redirectTo: '/flights', pathMatch: 'full' },
  { path: 'flights', component: FlightsComponent },
  { path: 'travelers', component: TravelersComponent },
  { path: 'bookings', component: BookingsComponent },
  { path: 'conferences', component: ConferencesComponent },
  { path: 'notifications', component: NotificationsComponent }
];
