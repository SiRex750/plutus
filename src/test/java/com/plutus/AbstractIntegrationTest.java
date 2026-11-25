package com.plutus;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests. One Postgres 16 container is started per JVM (singleton
 * pattern, deliberately not {@code @Container}, which would stop it after each test class) and
 * shared by every subclass; Spring also caches the context across them.
 * Docker is required: without it these tests fail rather than being skipped.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
