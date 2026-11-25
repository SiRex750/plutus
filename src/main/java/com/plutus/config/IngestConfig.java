package com.plutus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.plutus.ingest.NarrationParser;

/** Exposes the plain-Java ingest components as beans. */
@Configuration
public class IngestConfig {

    @Bean
    public NarrationParser narrationParser() {
        return new NarrationParser();
    }
}
