package io.hrc.sample;

import io.hrc.autoconfigure.annotation.EnableHighConcurrencyResource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo: Concert Ticket Booking System.
 * Shows framework usage: @EnableHighConcurrencyResource + YAML config.
 */
@SpringBootApplication
@EnableHighConcurrencyResource
public class SampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
