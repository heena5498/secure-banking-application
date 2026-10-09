package com.securebank.support;

import com.securebank.account.SystemAccounts;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database-level checks of double-entry ledger invariants. All money comparisons use
 * {@link BigDecimal#compareTo}; nothing is converted to floating point.
 */
public final class LedgerAssertions {

    public record Entry(UUID accountId, String entryType, BigDecimal amount) {
    }

    private final JdbcTemplate jdbcTemplate;

    public LedgerAssertions(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Entry> entriesFor(UUID transactionId) {
        return jdbcTemplate.query(
                "select account_id, entry_type, amount from ledger_entries where transaction_id = ?",
                (rs, row) -> new Entry(rs.getObject("account_id", UUID.class), rs.getString("entry_type"),
                        rs.getBigDecimal("amount")),
                transactionId);
    }

    /** The transaction is COMPLETED, has entries, and SUM(DEBIT) == SUM(CREDIT). */
    public List<Entry> assertCompletedAndBalanced(UUID transactionId) {
        assertThat(jdbcTemplate.queryForObject("select status from transactions where id = ?", String.class,
                transactionId)).isEqualTo("COMPLETED");
        List<Entry> entries = entriesFor(transactionId);
        assertThat(entries).as("ledger entries for %s", transactionId).isNotEmpty();
        assertThat(total(entries, "DEBIT")).as("debits vs credits for %s", transactionId)
                .isEqualByComparingTo(total(entries, "CREDIT"));
        return entries;
    }

    /** Clearing DEBIT and customer CREDIT, both for {@code amount}. */
    public void assertDepositPosting(UUID transactionId, UUID accountId, String amount) {
        assertPosting(transactionId, SystemAccounts.CLEARING_ACCOUNT_ID, accountId, amount);
    }

    /** Customer DEBIT and clearing CREDIT, both for {@code amount}. */
    public void assertWithdrawalPosting(UUID transactionId, UUID accountId, String amount) {
        assertPosting(transactionId, accountId, SystemAccounts.CLEARING_ACCOUNT_ID, amount);
    }

    /** Source DEBIT and destination CREDIT, with no clearing account involved. */
    public void assertTransferPosting(UUID transactionId, UUID sourceId, UUID destinationId, String amount) {
        assertPosting(transactionId, sourceId, destinationId, amount);
    }

    /** No ledger entries reference any non-COMPLETED transaction on this account. */
    public void assertNoEntriesForUncompletedTransactions(UUID accountId) {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from ledger_entries e join transactions t on t.id = e.transaction_id
                where t.status <> 'COMPLETED'
                  and (t.source_account_id = ? or t.destination_account_id = ?)""",
                Integer.class, accountId, accountId)).isZero();
    }

    /** An account's ledger position (credits minus debits) equals its stored balance. */
    public void assertLedgerMatchesBalance(UUID accountId) {
        BigDecimal position = jdbcTemplate.queryForObject("""
                select coalesce(sum(case when entry_type = 'CREDIT' then amount else -amount end), 0)
                from ledger_entries where account_id = ?""", BigDecimal.class, accountId);
        BigDecimal balance = jdbcTemplate.queryForObject("select balance from accounts where id = ?",
                BigDecimal.class, accountId);
        assertThat(position).as("ledger position of %s", accountId).isEqualByComparingTo(balance);
    }

    private void assertPosting(UUID transactionId, UUID debitAccountId, UUID creditAccountId, String amount) {
        List<Entry> entries = assertCompletedAndBalanced(transactionId);
        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.accountId()).isEqualTo(debitAccountId);
            assertThat(entry.entryType()).isEqualTo("DEBIT");
            assertThat(entry.amount()).isEqualByComparingTo(amount);
        });
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.accountId()).isEqualTo(creditAccountId);
            assertThat(entry.entryType()).isEqualTo("CREDIT");
            assertThat(entry.amount()).isEqualByComparingTo(amount);
        });
    }

    private static BigDecimal total(List<Entry> entries, String type) {
        return entries.stream().filter(e -> e.entryType().equals(type)).map(Entry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
