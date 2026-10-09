package com.securebank.postgres;

import com.securebank.account.SystemAccounts;
import com.securebank.support.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the context runs on PostgreSQL 16 with every Flyway migration applied and the
 * schema accepted by Hibernate validation (otherwise the context would fail to start).
 */
class PostgresSchemaTest extends PostgresIntegrationTestBase {

    @Test
    void runsOnPostgres16WithAllMigrationsApplied() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql://");
        }
        assertThat(jdbcTemplate.queryForObject("show server_version", String.class)).startsWith("16.");
        assertThat(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3");
        assertThat(jdbcTemplate.queryForObject(
                "select account_type from accounts where id = ?", String.class, SystemAccounts.CLEARING_ACCOUNT_ID))
                .isEqualTo("SYSTEM_CLEARING");
    }
}
