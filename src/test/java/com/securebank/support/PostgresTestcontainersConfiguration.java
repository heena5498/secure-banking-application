package com.securebank.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a PostgreSQL 16 container and points the application's DataSource at it via
 * {@link ServiceConnection}, overriding the H2 URL from the test profile.
 *
 * <p>The container is a Spring bean, so it lives as long as the cached test context: it starts once,
 * is shared by every PostgreSQL test class, and is stopped when the context closes (Testcontainers'
 * Ryuk sidecar also removes it if the JVM dies). Declaring it as a static {@code @Container} field
 * instead would restart it per test class while Spring kept reusing a context bound to the old port.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
