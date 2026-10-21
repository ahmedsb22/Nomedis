import { Routes } from '@angular/router';
import { FlightsComponent } from './components/flights/flights.component';
import { BookingsComponent } from './components/bookings/bookings.component';
import { TravelersComponent } from './components/travelers/travelers.component';

export const routes: Routes = [
  { path: '', redirectTo: '/flights', pathMatch: 'full' },
  { path: 'flights', component: FlightsComponent },
  { path: 'bookings', component: BookingsComponent },
  { path: 'travelers', component: TravelersComponent }
];
