package com.plutus.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of "now" for the application. Services inject {@link Clock}
 * instead of calling {@code LocalDate.now()} / {@code Instant.now()} directly,
 * so tests can substitute a fixed clock.
 *
 * <p>The clock's zone is the merchant's business zone ({@code plutus.timezone}, default Asia/Kolkata).
 * It is used wherever a calendar date must become an instant (e.g. a statement date → start of that day).
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${plutus.timezone:Asia/Kolkata}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
