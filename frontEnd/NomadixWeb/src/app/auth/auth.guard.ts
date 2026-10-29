import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { KeycloakService } from './keycloak.service';

/**
 * Protege les routes qui affichent des donnees personnelles
 * (voyageurs, reservations, notifications).
 *
 * Si l'utilisateur n'est pas connecte, il est redirige vers Keycloak puis
 * revient exactement sur la page demandee.
 */
export const authGuard: CanActivateFn = async (route, state) => {
  const keycloak = inject(KeycloakService);
  if (keycloak.isAuthenticated()) {
    return true;
  }
  await keycloak.login(state.url);
  return false;
};
