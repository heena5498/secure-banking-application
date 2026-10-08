package com.securebank.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.support.IntegrationTestBase;
import com.securebank.transaction.TransactionResponse;
import com.securebank.transaction.TransactionService;
import com.securebank.transaction.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestUser alice;
    private TestUser bob;
    private String aliceAccountId;
    private String bobAccountId;
    private String bobAccountNumber;

    /** Alice = $1,000, Bob = $0. */
    @BeforeEach
    void setUpAccounts() throws Exception {
        alice = registerAndLogin("Alice");
        bob = registerAndLogin("Bob");
        aliceAccountId = createAccount(alice, "CHECKING").get("id").asText();
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        bobAccountId = bobAccount.get("id").asText();
        bobAccountNumber = bobAccount.get("accountNumber").asText();
        deposit(alice, aliceAccountId, "1000").andExpect(status().isOk());
    }

    @Test
    void retriedTransferMovesMoneyOnceAndReturnsOriginalResult() throws Exception {
        JsonNode first = json(transfer(alice, aliceAccountId, bobAccountNumber, "250", "transfer-001")
                .andExpect(status().isCreated()));
        JsonNode retry = json(transfer(alice, aliceAccountId, bobAccountNumber, "250", "transfer-001")
                .andExpect(status().isCreated()));

        assertThat(retry).isEqualTo(first);
        assertBalances("750.00", "250.00");
        assertThat(transferCount()).isEqualTo(1);
        assertSingleBalancedPair(first.get("id").asText());
    }

    @Test
    void reusingKeyForDifferentTransferIsRejectedWithoutMovingMoney() throws Exception {
        transfer(alice, aliceAccountId, bobAccountNumber, "100", "transfer-002").andExpect(status().isCreated());

        transfer(alice, aliceAccountId, bobAccountNumber, "500", "transfer-002")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("Idempotency key has already been used for a different request"));

        assertBalances("900.00", "100.00");
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    void retriedDepositIncreasesBalanceOnce() throws Exception {
        JsonNode first = json(deposit(alice, aliceAccountId, "500", "deposit-001").andExpect(status().isOk()));
        JsonNode retry = json(deposit(alice, aliceAccountId, "500.00", "deposit-001").andExpect(status().isOk()));

        assertThat(retry.get("id")).isEqualTo(first.get("id"));
        assertBalances("1500.00", "0.00");
        assertSingleBalancedPair(first.get("id").asText());
    }

    @Test
    void retriedWithdrawalDecreasesBalanceOnce() throws Exception {
        JsonNode first = json(withdraw(alice, aliceAccountId, "100", "withdrawal-001").andExpect(status().isOk()));
        JsonNode retry = json(withdraw(alice, aliceAccountId, "100", "withdrawal-001").andExpect(status().isOk()));

        assertThat(retry.get("id")).isEqualTo(first.get("id"));
        assertBalances("900.00", "0.00");
        assertSingleBalancedPair(first.get("id").asText());
    }

    @Test
    void simultaneousDuplicateTransfersMoveMoneyOnce() throws Exception {
        TransferRequest request = new TransferRequest(UUID.fromString(aliceAccountId), bobAccountNumber,
                new BigDecimal("250.00"), null);
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransactionResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return transactionService.transfer(alice.id(), "concurrent-001", request);
                }));
            }
            start.countDown();
            List<UUID> transactionIds = new ArrayList<>();
            for (Future<TransactionResponse> future : futures) {
                transactionIds.add(future.get(30, TimeUnit.SECONDS).id());
            }

            assertThat(transactionIds).hasSize(threads).containsOnly(transactionIds.get(0));
            assertBalances("750.00", "250.00");
            assertThat(transferCount()).isEqualTo(1);
            assertSingleBalancedPair(transactionIds.get(0).toString());
        } finally {
            executor.shutdownNow();
        }
    }

    /** The V2.2 Definition of Done scenario. */
    @Test
    void tenRetriesThenConflictingReuse() throws Exception {
        String original = json(transfer(alice, aliceAccountId, bobAccountNumber, "250", "payment-123")
                .andExpect(status().isCreated())).get("id").asText();
        for (int retry = 0; retry < 10; retry++) {
            transfer(alice, aliceAccountId, bobAccountNumber, "250", "payment-123")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(original));
        }
        assertBalances("750.00", "250.00");

        transfer(alice, aliceAccountId, bobAccountNumber, "500", "payment-123")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertBalances("750.00", "250.00");
        assertThat(transferCount()).isEqualTo(1);
        assertSingleBalancedPair(original);
        assertThat(jdbcTemplate.queryForObject("""
                select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0)
                from ledger_entries""", BigDecimal.class)).isEqualByComparingTo("0");
    }

    @Test
    void missingOrMalformedKeyIsRejectedWithoutMovingMoney() throws Exception {
        Map<String, Object> body = Map.of("amount", new BigDecimal("10"));

        perform(post("/api/accounts/{id}/withdraw", aliceAccountId), alice.token(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        perform(post("/api/accounts/{id}/deposit", aliceAccountId), alice.token(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        transfer(alice, aliceAccountId, bobAccountNumber, "10", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        perform(post("/api/accounts/{id}/deposit", aliceAccountId).header("Idempotency-Key", "not valid!"),
                alice.token(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));

        assertBalances("1000.00", "0.00");
    }

    @Test
    void keysAreScopedToTheAuthenticatedUser() throws Exception {
        deposit(alice, aliceAccountId, "10", "shared-key").andExpect(status().isOk());
        deposit(bob, bobAccountId, "20", "shared-key").andExpect(status().isOk());

        assertBalances("1010.00", "20.00");
    }

    @Test
    void keyCannotBeReusedForADifferentOperation() throws Exception {
        deposit(alice, aliceAccountId, "100", "multi-use").andExpect(status().isOk());

        withdraw(alice, aliceAccountId, "100", "multi-use")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertBalances("1100.00", "0.00");
    }

    @Test
    void rejectedRequestDoesNotConsumeItsKey() throws Exception {
        withdraw(alice, aliceAccountId, "1500", "withdrawal-retry")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        deposit(alice, aliceAccountId, "500").andExpect(status().isOk());

        withdraw(alice, aliceAccountId, "1500", "withdrawal-retry").andExpect(status().isOk());

        assertBalances("0.00", "0.00");
    }

    private void assertBalances(String aliceBalance, String bobBalance) throws Exception {
        assertThat(balanceOf(alice, aliceAccountId).decimalValue()).isEqualByComparingTo(aliceBalance);
        assertThat(balanceOf(bob, bobAccountId).decimalValue()).isEqualByComparingTo(bobBalance);
    }

    private int transferCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from transactions
                where transaction_type = 'TRANSFER' and status = 'COMPLETED' and source_account_id = ?""",
                Integer.class, UUID.fromString(aliceAccountId));
    }

    private void assertSingleBalancedPair(String transactionId) {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                select count(*) as entries,
                       coalesce(sum(case when entry_type = 'DEBIT' then amount end), 0) as debits,
                       coalesce(sum(case when entry_type = 'CREDIT' then amount end), 0) as credits
                from ledger_entries where transaction_id = ?""", UUID.fromString(transactionId));
        assertThat(((Number) totals.get("entries")).intValue()).isEqualTo(2);
        assertThat((BigDecimal) totals.get("debits")).isEqualByComparingTo((BigDecimal) totals.get("credits"));
    }
}
