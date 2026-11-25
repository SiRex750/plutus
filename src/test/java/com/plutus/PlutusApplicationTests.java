package com.plutus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full Spring context against a real Postgres 16 in Docker.
 * Docker is required: if it is unavailable this test FAILS rather than being skipped
 * (no {@code disabledWithoutDocker}), so a missing Docker daemon is never silent.
 */
@SpringBootTest
@Testcontainers
class PlutusApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoads() {
    }
}
