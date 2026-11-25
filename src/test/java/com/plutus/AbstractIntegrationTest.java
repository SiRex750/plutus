package com.plutus;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests. One Postgres 16 container is started per JVM (singleton
 * pattern, deliberately not {@code @Container}, which would stop it after each test class) and
 * shared by every subclass; all subclasses also share one cached Spring context (MockMvc included).
 * Docker is required: without it these tests fail rather than being skipped.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @AfterEach
    void truncateAllTables() {
        jdbc.execute("TRUNCATE allocations, payments, invoices RESTART IDENTITY CASCADE");
    }
}
