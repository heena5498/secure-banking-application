package com.securebank.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.support.ConcurrentRunner;
import com.securebank.support.ConcurrentRunner.Outcome;
import com.securebank.support.LedgerAssertions;
import com.securebank.support.PostgresIntegrationTestBase;
import com.securebank.support.RowLockBarrier;
import com.securebank.transaction.TransactionResponse;
import com.securebank.transaction.TransactionService;
import com.securebank.transaction.TransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Timeout(90)
class ConcurrentIdempotencyPostgresTest extends PostgresIntegrationTestBase {

    @Autowired
    private TransactionService transactionService;

    /**
     * Both requests pass the "no previous result" check before either inserts its idempotency record.
     * The barrier locks Alice's user row, which blocks each request's idempotency insert at its foreign
     * key check, so both are provably between the check and the insert when released. Only PostgreSQL's
     * UNIQUE (user_id, idempotency_key) constraint can then stop the second transfer.
     */
    @Test
    void simultaneousRequestsWithSameKeyMoveMoneyOnce() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        UUID aliceAccountId = UUID.fromString(createAccount(alice, "CHECKING").get("id").asText());
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        UUID bobAccountId = UUID.fromString(bobAccount.get("id").asText());
        deposit(alice, aliceAccountId.toString(), "1000").andExpect(status().isOk());

        TransferRequest request = new TransferRequest(aliceAccountId, bobAccount.get("accountNumber").asText(),
                new BigDecimal("250.00"), null);
        Callable<TransactionResponse> sameTransfer =
                () -> transactionService.transfer(alice.id(), "payment-123", request);

        List<Outcome<TransactionResponse>> outcomes;
        try (RowLockBarrier barrier = RowLockBarrier.lockRows(dataSource, jdbcTemplate, "users", alice.id());
             ConcurrentRunner<TransactionResponse> runner = new ConcurrentRunner<>(List.of(sameTransfer, sameTransfer))) {
            runner.start();
            barrier.awaitLockWaiters(2);
            barrier.release();
            outcomes = runner.awaitOutcomes();
        }

        // Both callers get the same successful result; the loser of the race replays the winner's.
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        UUID transactionId = outcomes.get(0).result().id();
        assertThat(outcomes.get(1).result().id()).isEqualTo(transactionId);

        assertThat(balance(aliceAccountId)).isEqualByComparingTo("750.00");
        assertThat(balance(bobAccountId)).isEqualByComparingTo("250.00");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from transactions where source_account_id = ? and transaction_type = 'TRANSFER'""",
                Integer.class, aliceAccountId)).isEqualTo(1);
        new LedgerAssertions(jdbcTemplate)
                .assertTransferPosting(transactionId, aliceAccountId, bobAccountId, "250.00");

        List<Map<String, Object>> records = jdbcTemplate.queryForList("""
                select status, transaction_id from idempotency_records
                where user_id = ? and idempotency_key = 'payment-123'""", alice.id());
        assertThat(records).hasSize(1);
        assertThat(records.get(0)).containsEntry("status", "COMPLETED").containsEntry("transaction_id", transactionId);
    }

    private BigDecimal balance(UUID accountId) {
        return jdbcTemplate.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }
}
