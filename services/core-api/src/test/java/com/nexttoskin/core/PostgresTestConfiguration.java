package com.nexttoskin.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts the same PostgreSQL + pgvector image as docker-compose.yml for tests.
 * {@code @ServiceConnection} points Spring's datasource at the container, so Flyway runs the real
 * migrations against it. Spring caches the test context, so all DB tests share one container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(PGVECTOR);
    }
}
