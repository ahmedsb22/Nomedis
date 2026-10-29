package com.nomadix.booking.client;

import com.nomadix.booking.dto.TravelerDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/** Validation synchrone du voyageur via Eureka (aucune URL en dur). */
@FeignClient(name = "traveler-service", fallbackFactory = TravelerClientFallbackFactory.class)
public interface TravelerClient {

    @GetMapping("/api/travelers/{id}")
    TravelerDTO getTravelerById(@PathVariable("id") Long id,
                                @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);
}
