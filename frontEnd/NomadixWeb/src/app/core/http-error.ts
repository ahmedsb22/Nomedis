import { HttpErrorResponse } from '@angular/common/http';

/**
 * Traduit une erreur HTTP en message lisible pour l'utilisateur.
 *
 * Les services renvoient un corps JSON homogene
 * (`{ status, error, message }` cote Spring, `{ detail }` cote FastAPI) : on
 * l'affiche plutot qu'un code brut. Les cas 401/403 sont traites a part, car le
 * vrai message est souvent masque par la redirection Keycloak.
 */
export function httpErrorMessage(error: HttpErrorResponse): string {
  if (error.status === 0) {
    return (
      'Service injoignable : verifiez que la pile tourne (docker compose ps), ' +
      'puis rechargez la page. Frontend http://localhost:8090, ' +
      'API Gateway http://localhost:9089.'
    );
  }
  if (error.status === 401) {
    return 'Session expiree ou jeton absent : reconnectez-vous via le bouton « Se connecter ».';
  }

  const serveur = serverMessage(error);

  if (error.status === 403) {
    // La Gateway repond « Role insuffisant : cette operation exige le role ADMIN ».
    // On affiche donc la raison renvoyee par le serveur au lieu d'un texte figé :
    // si l'utilisateur se croit connecte en admin, il voit tout de suite que le
    // jeton recu n'a pas le role attendu.
    return serveur
      ? `Acces refuse (403) : ${serveur} Verifiez le compte connecte (le badge en haut a droite affiche ADMIN ou TRAVELER).`
      : "Acces refuse : cette action exige le role ADMIN (connectez-vous avec le compte admin / admin123).";
  }

  if (serveur) {
    return serveur;
  }
  return `Erreur HTTP ${error.status} ${error.statusText || ''}`.trim();
}

/** Extrait le message metier d'un corps d'erreur JSON ou texte. */
function serverMessage(error: HttpErrorResponse): string | null {
  const body: unknown = error.error;
  if (typeof body === 'string' && body.trim().length > 0) {
    return body.trim();
  }
  if (body && typeof body === 'object') {
    const record = body as Record<string, unknown>;
    for (const key of ['message', 'detail', 'error']) {
      const value = record[key];
      if (typeof value === 'string' && value.trim().length > 0) {
        return value.trim();
      }
    }
  }
  return null;
}
