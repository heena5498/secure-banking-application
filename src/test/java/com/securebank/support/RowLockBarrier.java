package com.securebank.support;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Forces genuine overlap between concurrent requests. The barrier holds {@code FOR UPDATE} row locks
 * in its own open transaction; workers that need those rows block inside PostgreSQL. The test waits
 * until PostgreSQL reports the expected number of sessions waiting on locks, which proves every
 * worker is in flight at the same moment, then releases the barrier so they contend for real.
 *
 * <p>Without this, a "concurrent" test can pass simply because the threads happened to run one
 * after another.
 */
public final class RowLockBarrier implements AutoCloseable {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

    private final Connection connection;
    private final JdbcTemplate jdbcTemplate;
    private boolean released;

    private RowLockBarrier(Connection connection, JdbcTemplate jdbcTemplate) {
        this.connection = connection;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Locks the given rows of {@code table} (by {@code id}) until {@link #release()}.
     * Table names come from test code only.
     */
    public static RowLockBarrier lockRows(DataSource dataSource, JdbcTemplate jdbcTemplate, String table,
                                          UUID... ids) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            for (UUID id : ids) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "select id from " + table + " where id = ? for update")) {
                    statement.setObject(1, id);
                    statement.executeQuery().close();
                }
            }
            return new RowLockBarrier(connection, jdbcTemplate);
        } catch (SQLException | RuntimeException ex) {
            connection.close();
            throw ex;
        }
    }

    /** Blocks (bounded) until at least {@code expected} other sessions are waiting on a lock. */
    public void awaitLockWaiters(int expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        int waiting = 0;
        while (Instant.now().isBefore(deadline)) {
            waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock'
                      and pid <> pg_backend_pid()""", Integer.class);
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected " + expected + " sessions waiting on locks, saw " + waiting);
    }

    /** Ends the barrier transaction, letting the blocked workers proceed. */
    public void release() throws SQLException {
        if (!released) {
            released = true;
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Override
    public void close() throws SQLException {
        release();
    }
}
