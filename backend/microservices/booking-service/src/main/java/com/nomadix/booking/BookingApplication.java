package com.nomadix.booking;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients // Active la communication avec les autres microservices
public class BookingApplication {
    public static void main(String[] args) { SpringApplication.run(BookingApplication.class, args); }
}