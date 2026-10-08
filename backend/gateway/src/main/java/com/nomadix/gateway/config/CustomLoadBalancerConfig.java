package com.nomadix.gateway.config;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@LoadBalancerClient(name = "TRAVELER-SERVICE", configuration = CustomLoadBalancerConfig.class)
public class CustomLoadBalancerConfig {

    @Bean
    public ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(
            Environment environment,
            ServiceInstanceListSupplier supplier) {
        
        String serviceId = "TRAVELER-SERVICE";
        // Utilisation de l'algorithme Random au lieu du Round Robin par défaut
        return new RandomLoadBalancer(supplier, serviceId);
    }
}