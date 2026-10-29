import { Injectable, signal } from '@angular/core';
import Keycloak from 'keycloak-js';
import { environment } from '../../environments/environment';

/**
 * Integration Keycloak cote Angular (SPA publique, Authorization Code + PKCE).
 *
 * Le SPA ne stocke jamais de mot de passe : il est redirige vers Keycloak,
 * revient avec un code, l'echange contre un jeton (PKCE) et conserve ce jeton
 * en memoire. Le jeton est ensuite ajoute a chaque appel /api par l'intercepteur.
 */
@Injectable({ providedIn: 'root' })
export class KeycloakService {
  private keycloak: Keycloak | null = null;

  /** Etat expose aux composants (signals Angular). */
  readonly authenticated = signal(false);
  readonly username = signal<string | null>(null);
  readonly roles = signal<string[]>([]);

  /** Appele au demarrage de l'application (provideAppInitializer). */
  async init(): Promise<void> {
    try {
      const kc = new Keycloak({
        url: environment.keycloak.url,
        realm: environment.keycloak.realm,
        clientId: environment.keycloak.clientId,
      });
      this.keycloak = kc;

      const authenticated = await kc.init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        checkLoginIframe: false,
        flow: 'standard',
      });
      this.applyState(authenticated);
    } catch (error) {
      // Keycloak injoignable : l'application reste consultable en lecture publique.
      console.error('[Keycloak] Initialisation impossible :', error);
      this.authenticated.set(false);
    }
  }

  /** Redirige vers la page de connexion Keycloak. */
  async login(redirectPath: string = window.location.pathname): Promise<void> {
    await this.keycloak?.login({
      redirectUri: `${window.location.origin}${redirectPath}`,
    });
  }

  /** Deconnecte l'utilisateur (session Keycloak + session locale). */
  async logout(): Promise<void> {
    await this.keycloak?.logout({
      redirectUri: window.location.origin,
    });
  }

  isAuthenticated(): boolean {
    return this.authenticated();
  }

  hasRole(role: string): boolean {
    return this.roles().includes(role);
  }

  /**
   * Retourne un jeton encore valide au moins 30 s, en le rafraichissant si besoin.
   * Utilise par l'intercepteur HTTP avant chaque appel /api.
   */
  async ensureFreshToken(): Promise<string | null> {
    const kc = this.keycloak;
    if (!kc || !kc.authenticated) {
      return null;
    }
    try {
      await kc.updateToken(30);
      this.applyState(true);
      return kc.token ?? null;
    } catch {
      console.warn('[Keycloak] Session expiree : reconnexion necessaire.');
      this.applyState(false);
      return null;
    }
  }

  private applyState(authenticated: boolean): void {
    this.authenticated.set(authenticated);
    const profile = this.keycloak?.tokenParsed as Record<string, unknown> | undefined;
    this.username.set(
      authenticated && profile
        ? ((profile['preferred_username'] as string) ?? (profile['email'] as string) ?? null)
        : null,
    );
    this.roles.set(
      authenticated ? ((this.keycloak?.realmAccess?.roles as string[]) ?? []) : [],
    );
  }
}
