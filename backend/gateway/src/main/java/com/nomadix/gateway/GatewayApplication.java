package com.nomadix.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API Gateway Nomadix.
 *
 * Les routes sont declarees uniquement dans application.yml
 * (spring.cloud.gateway.routes) : une seule source de verite, pour eviter
 * deux tables de routage divergentes.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
