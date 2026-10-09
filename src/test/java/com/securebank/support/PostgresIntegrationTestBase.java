package com.securebank.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Base class for integration tests that must run against real PostgreSQL rather than H2.
 * Requires a running Docker daemon.
 *
 * <p>Flyway applies the production migrations to the container and Hibernate validates the
 * resulting schema, exactly as at application startup. Tests are isolated by creating their own
 * users and accounts (unique emails), not by rolling back: test methods must not be
 * {@code @Transactional}, because worker threads in concurrency tests need committed setup data and
 * their own independent transactions.
 */
@Import(PostgresTestcontainersConfiguration.class)
public abstract class PostgresIntegrationTestBase extends IntegrationTestBase {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected DataSource dataSource;
}
