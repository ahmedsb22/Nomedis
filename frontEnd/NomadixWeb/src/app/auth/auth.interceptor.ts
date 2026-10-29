import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';
import { KeycloakService } from './keycloak.service';

/**
 * Ajoute l'en-tete `Authorization: Bearer <jwt>` a tous les appels `/api/**`.
 *
 * Le jeton est rafraichi si sa validite restante est inferieure a 30 secondes,
 * ce qui evite les 401 aleatoires pendant une session longue.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Seules les routes de l'API passe par la Gateway ont besoin du jeton.
  if (!req.url.startsWith('/api')) {
    return next(req);
  }

  const keycloak = inject(KeycloakService);
  return from(keycloak.ensureFreshToken()).pipe(
    switchMap((token) =>
      next(
        token
          ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
          : req,
      ),
    ),
  );
};
