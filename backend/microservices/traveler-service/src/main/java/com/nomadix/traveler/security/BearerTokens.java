package com.nomadix.traveler.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capture le jeton porteur de la requete HTTP courante.
 *
 * <p>Le circuit breaker Resilience4j execute les appels Feign dans un
 * {@code CompletableFuture} : le {@code SecurityContext} (thread-local) n'est
 * plus disponible sur ce thread. On capture donc le jeton avant l'appel et on
 * le transmet explicitement en en-tete {@code Authorization}.</p>
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
