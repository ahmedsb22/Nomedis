package com.nomadix.booking.client;

import com.nomadix.booking.dto.FlightDTO;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FlightClientFallbackFactory implements FallbackFactory<FlightClient> {

    private static final Logger log = LoggerFactory.getLogger(FlightClientFallbackFactory.class);

    @Override
    public FlightClient create(final Throwable cause) {
        log.warn("[CIRCUIT BREAKER] Flight-Service indisponible -> fallback. Cause : {}", cause.toString());
        return new FlightClient() {
            @Override
            public FlightDTO getFlightById(Long id, String authorization) {
                // Un 404 est une reponse metier, pas une panne : on la propage telle quelle.
                if (cause instanceof FeignException.NotFound) {
                    throw (FeignException) cause;
                }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Flight-Service indisponible : timeout ou circuit breaker OPEN");
            }

            @Override
            public FlightDTO reserveSeat(Long id, String authorization) {
                if (cause instanceof FeignException.NotFound) {
                    throw (FeignException) cause;
                }
                // 409 = vol complet : erreur metier a conserver.
                if (cause instanceof FeignException.Conflict) {
                    throw (FeignException) cause;
                }
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Flight-Service indisponible : impossible de reserver une place");
            }

            @Override
            public FlightDTO releaseSeat(Long id, String authorization) {
                // Compensation en mode degrade : on ne peut pas restituer la place,
                // on trace pour un traitement manuel.
                log.error("[COMPENSATION] Impossible de restituer la place du vol {} : {}", id, cause.toString());
                return null;
            }
        };
    }
}
