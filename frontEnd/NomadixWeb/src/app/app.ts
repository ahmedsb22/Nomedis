import { Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { KeycloakService } from './auth/keycloak.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly title = signal('Nomadix');
  protected readonly auth = inject(KeycloakService);

  protected login(): void {
    void this.auth.login();
  }

  protected logout(): void {
    void this.auth.logout();
  }
}
