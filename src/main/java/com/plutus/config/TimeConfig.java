package com.plutus.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of "now" for the application. Services inject {@link Clock}
 * instead of calling {@code LocalDate.now()} / {@code Instant.now()} directly,
 * so tests can substitute a fixed clock.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
