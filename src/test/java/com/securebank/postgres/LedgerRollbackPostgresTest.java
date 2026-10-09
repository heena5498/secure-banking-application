package com.securebank.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.support.LedgerAssertions;
import com.securebank.support.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerRollbackPostgresTest extends PostgresIntegrationTestBase {

    private static final String REJECT_CONSTRAINT = "ck_test_reject_200_credit";

    /**
     * A test-only CHECK constraint makes PostgreSQL reject the transfer's CREDIT ledger entry. The
     * rows are written when the transaction flushes at commit, in persist order: the transfer record,
     * then the DEBIT entry, then the failing CREDIT entry. So the transfer record and half of the
     * ledger posting really are inserted before the failure, and the balance updates are pending in
     * the same transaction. All of it must roll back.
     */
    @Test
    void ledgerInsertFailureRollsBackEntireTransfer() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        UUID aliceAccountId = UUID.fromString(createAccount(alice, "CHECKING").get("id").asText());
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        UUID bobAccountId = UUID.fromString(bobAccount.get("id").asText());
        deposit(alice, aliceAccountId.toString(), "1000").andExpect(status().isOk());
        deposit(bob, bobAccountId.toString(), "500").andExpect(status().isOk());

        // NOT VALID: existing rows (from other tests) are not checked, only new inserts.
        jdbcTemplate.execute("alter table ledger_entries add constraint " + REJECT_CONSTRAINT
                + " check (not (entry_type = 'CREDIT' and amount = 200.00)) not valid");
        try {
            // The database error surfaces as the API's generic 409 CONFLICT for integrity violations.
            transfer(alice, aliceAccountId.toString(), bobAccount.get("accountNumber").asText(), "200",
                    "rollback-001")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"));
        } finally {
            jdbcTemplate.execute("alter table ledger_entries drop constraint if exists " + REJECT_CONSTRAINT);
        }

        assertThat(balance(aliceAccountId)).isEqualByComparingTo("1000.00");
        assertThat(balance(bobAccountId)).isEqualByComparingTo("500.00");
        // No transfer record of any status: this was a system failure, not a business rejection, so no
        // FAILED record is written either.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from transactions where source_account_id = ? and transaction_type = 'TRANSFER'""",
                Integer.class, aliceAccountId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from ledger_entries where account_id in (?, ?) and amount = 200.00""",
                Integer.class, aliceAccountId, bobAccountId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from idempotency_records where user_id = ? and idempotency_key = 'rollback-001'""",
                Integer.class, alice.id())).isZero();
        LedgerAssertions ledger = new LedgerAssertions(jdbcTemplate);
        ledger.assertLedgerMatchesBalance(aliceAccountId);
        ledger.assertLedgerMatchesBalance(bobAccountId);

        // The rolled-back request did not consume its key: once the fault is gone, a retry succeeds once.
        String transactionId = json(transfer(alice, aliceAccountId.toString(),
                bobAccount.get("accountNumber").asText(), "200", "rollback-001")
                .andExpect(status().isCreated())).get("id").asText();
        assertThat(balance(aliceAccountId)).isEqualByComparingTo("800.00");
        assertThat(balance(bobAccountId)).isEqualByComparingTo("700.00");
        ledger.assertTransferPosting(UUID.fromString(transactionId), aliceAccountId, bobAccountId, "200.00");
    }

    private BigDecimal balance(UUID accountId) {
        return jdbcTemplate.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }
}
