package com.securebank.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.common.InsufficientFundsException;
import com.securebank.support.ConcurrentRunner;
import com.securebank.support.ConcurrentRunner.Outcome;
import com.securebank.support.LedgerAssertions;
import com.securebank.support.PostgresIntegrationTestBase;
import com.securebank.support.RowLockBarrier;
import com.securebank.transaction.AmountRequest;
import com.securebank.transaction.TransactionResponse;
import com.securebank.transaction.TransactionService;
import com.securebank.transaction.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Concurrent balance-changing operations against real PostgreSQL row locks. Worker threads call
 * {@link TransactionService} directly, so each runs in its own database transaction exactly as an
 * HTTP request would; setup data is committed through the API before any worker starts.
 */
@Timeout(90)
class ConcurrentFinancialOperationsPostgresTest extends PostgresIntegrationTestBase {

    @Autowired
    private TransactionService transactionService;

    private LedgerAssertions ledger;

    @BeforeEach
    void setUpLedgerAssertions() {
        ledger = new LedgerAssertions(jdbcTemplate);
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawAccount() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        UUID accountId = UUID.fromString(createAccount(alice, "CHECKING").get("id").asText());
        deposit(alice, accountId.toString(), "1000").andExpect(status().isOk());

        List<Outcome<TransactionResponse>> outcomes = runOverlapping(List.of(
                () -> transactionService.withdraw(alice.id(), accountId, "withdrawal-A",
                        new AmountRequest(new BigDecimal("800.00"), null)),
                () -> transactionService.withdraw(alice.id(), accountId, "withdrawal-B",
                        new AmountRequest(new BigDecimal("800.00"), null))),
                accountId);

        List<Outcome<TransactionResponse>> succeeded = outcomes.stream().filter(Outcome::succeeded).toList();
        List<Outcome<TransactionResponse>> failed = outcomes.stream().filter(o -> !o.succeeded()).toList();
        assertThat(succeeded).hasSize(1);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).error()).isInstanceOf(InsufficientFundsException.class);

        BigDecimal balance = balance(accountId);
        assertThat(balance).isEqualByComparingTo("200.00");
        assertThat(balance.signum()).isNotNegative();
        assertThat(countTransactions(accountId, "WITHDRAWAL", "COMPLETED")).isEqualTo(1);
        assertThat(countTransactions(accountId, "WITHDRAWAL", "FAILED")).isEqualTo(1);
        ledger.assertWithdrawalPosting(succeeded.get(0).result().id(), accountId, "800.00");
        ledger.assertNoEntriesForUncompletedTransactions(accountId);
        ledger.assertLedgerMatchesBalance(accountId);
    }

    @Test
    void opposingConcurrentTransfersPreserveBalancesWithoutDeadlock() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        JsonNode aliceAccount = createAccount(alice, "CHECKING");
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        UUID aliceId = UUID.fromString(aliceAccount.get("id").asText());
        UUID bobId = UUID.fromString(bobAccount.get("id").asText());
        deposit(alice, aliceId.toString(), "1000").andExpect(status().isOk());
        deposit(bob, bobId.toString(), "500").andExpect(status().isOk());

        List<Outcome<TransactionResponse>> outcomes = runOverlapping(List.of(
                () -> transactionService.transfer(alice.id(), "transfer-A", new TransferRequest(aliceId,
                        bobAccount.get("accountNumber").asText(), new BigDecimal("300.00"), null)),
                () -> transactionService.transfer(bob.id(), "transfer-B", new TransferRequest(bobId,
                        aliceAccount.get("accountNumber").asText(), new BigDecimal("200.00"), null))),
                aliceId, bobId);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(balance(aliceId)).isEqualByComparingTo("900.00");
        assertThat(balance(bobId)).isEqualByComparingTo("600.00");
        assertThat(balance(aliceId).add(balance(bobId))).isEqualByComparingTo("1500.00");
        ledger.assertTransferPosting(outcomes.get(0).result().id(), aliceId, bobId, "300.00");
        ledger.assertTransferPosting(outcomes.get(1).result().id(), bobId, aliceId, "200.00");
        ledger.assertLedgerMatchesBalance(aliceId);
        ledger.assertLedgerMatchesBalance(bobId);
    }

    /** Many opposing transfers released together, without a barrier, to vary the interleavings. */
    @Test
    void manyOpposingTransfersConserveMoney() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        JsonNode aliceAccount = createAccount(alice, "CHECKING");
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        UUID aliceId = UUID.fromString(aliceAccount.get("id").asText());
        UUID bobId = UUID.fromString(bobAccount.get("id").asText());
        deposit(alice, aliceId.toString(), "1000").andExpect(status().isOk());
        deposit(bob, bobId.toString(), "500").andExpect(status().isOk());

        List<Callable<TransactionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String key = "stress-" + i;
            if (i % 2 == 0) {
                tasks.add(() -> transactionService.transfer(alice.id(), key, new TransferRequest(aliceId,
                        bobAccount.get("accountNumber").asText(), new BigDecimal("15.00"), null)));
            } else {
                tasks.add(() -> transactionService.transfer(bob.id(), key, new TransferRequest(bobId,
                        aliceAccount.get("accountNumber").asText(), new BigDecimal("10.00"), null)));
            }
        }
        List<Outcome<TransactionResponse>> outcomes;
        try (ConcurrentRunner<TransactionResponse> runner = new ConcurrentRunner<>(tasks)) {
            outcomes = runner.start().awaitOutcomes();
        }

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        assertThat(balance(aliceId)).isEqualByComparingTo("950.00");
        assertThat(balance(bobId)).isEqualByComparingTo("550.00");
        outcomes.forEach(outcome -> ledger.assertCompletedAndBalanced(outcome.result().id()));
        ledger.assertLedgerMatchesBalance(aliceId);
        ledger.assertLedgerMatchesBalance(bobId);
    }

    /**
     * Starts the tasks while a barrier holds the given account rows, waits until PostgreSQL shows
     * every task blocked on those rows, then releases them so they truly contend.
     */
    private List<Outcome<TransactionResponse>> runOverlapping(List<Callable<TransactionResponse>> tasks,
                                                              UUID... lockedAccountIds) throws Exception {
        try (RowLockBarrier barrier = RowLockBarrier.lockRows(dataSource, jdbcTemplate, "accounts", lockedAccountIds);
             ConcurrentRunner<TransactionResponse> runner = new ConcurrentRunner<>(tasks)) {
            runner.start();
            barrier.awaitLockWaiters(tasks.size());
            barrier.release();
            return runner.awaitOutcomes();
        }
    }

    private BigDecimal balance(UUID accountId) {
        return jdbcTemplate.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }

    private int countTransactions(UUID sourceAccountId, String type, String status) {
        return jdbcTemplate.queryForObject("""
                select count(*) from transactions
                where source_account_id = ? and transaction_type = ? and status = ?""",
                Integer.class, sourceAccountId, type, status);
    }
}
