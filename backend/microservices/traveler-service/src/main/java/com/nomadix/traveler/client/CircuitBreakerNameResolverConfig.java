package com.nomadix.traveler.client;

import java.lang.reflect.Method;

import feign.Target;
import org.springframework.cloud.openfeign.CircuitBreakerNameResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Le circuit breaker prend le nom court du client Feign.
 *  Sans ce bean OpenFeign le nomme client_methode et la config
 *  resilience4j.circuitbreaker.instances.client ne sert a rien.
 */
@Configuration
public class CircuitBreakerNameResolverConfig {
    @Bean
    public CircuitBreakerNameResolver nomadixCircuitBreakerNameResolver() {
        return new CircuitBreakerNameResolver() {
            @Override
            public String resolveCircuitBreakerName(String feignClientName, Target<?> target, Method method) {
                return feignClientName;
            }
        };
    }
}