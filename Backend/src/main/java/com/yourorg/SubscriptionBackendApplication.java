package com.yourorg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>A {@code runFlyway} CommandLineRunner used to live here and call
 * {@code Flyway.migrate()} by hand, printing ">>> MANUAL FLYWAY". Together with
 * {@code Configuration/FlywayConfig} and {@code spring.flyway.enabled: true} that
 * made three separate code paths migrate the same database on every boot. Flyway
 * records what it has applied, so the extra runs were wasted rather than harmful --
 * but the hand-built instances ignored {@code spring.flyway.*} entirely, so the
 * settings in application.yml had no effect and the file described a configuration
 * that was not in force.
 *
 * <p>Migration is now Spring Boot's auto-configuration alone, driven by
 * {@code spring.flyway.*} in application.yml.
 */
@SpringBootApplication
public class SubscriptionBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubscriptionBackendApplication.class, args);
    }
}
