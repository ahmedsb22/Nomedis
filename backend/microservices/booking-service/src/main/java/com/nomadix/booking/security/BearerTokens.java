package com.nomadix.booking.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capture le jeton porteur de la requete HTTP courante.
 *
 * <p>Pourquoi ne pas simplement lire le {@code SecurityContext} dans un
 * intercepteur Feign ? Parce que le circuit breaker Resilience4j execute
 * l'appel Feign dans un {@code CompletableFuture} (thread du pool commun) :
 * le contexte de securite, stocke dans un thread-local, n'y est plus visible.
 * On capture donc le jeton <b>avant</b> l'appel asynchrone et on le transmet
 * explicitement en en-tete HTTP.</p>
 */
public final class BearerTokens {

    private BearerTokens() {
    }

    /** @return l'en-tete complet, par exemple {@code "Bearer eyJhbGciOi..."} */
    public static String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() instanceof AbstractOAuth2Token token) {
            return "Bearer " + token.getTokenValue();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Aucun jeton utilisateur disponible pour l'appel inter-services");
    }
}
