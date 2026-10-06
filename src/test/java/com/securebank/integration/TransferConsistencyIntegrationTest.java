package com.securebank.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.account.AccountRepository;
import com.securebank.account.AccountStatus;
import com.securebank.common.InsufficientFundsException;
import com.securebank.support.IntegrationTestBase;
import com.securebank.transaction.AmountRequest;
import com.securebank.transaction.TransactionService;
import com.securebank.transaction.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferConsistencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionService transactionService;

    @Test
    void rejectedTransferChangesNothingAndIsRecordedAsFailed() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        String aliceAccountId = createAccount(alice, "CHECKING").get("id").asText();
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        deposit(alice, aliceAccountId, "1000").andExpect(status().isOk());
        freeze(bobAccount.get("id").asText());

        transfer(alice, aliceAccountId, bobAccount.get("accountNumber").asText(), "200")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_FROZEN"));

        assertThat(balanceOf(alice, aliceAccountId).decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(accountRepository.findById(UUID.fromString(bobAccount.get("id").asText())).orElseThrow()
                .getBalance()).isEqualByComparingTo("0.00");
        getAuthenticated("/api/accounts/{id}/transactions", alice.token(), aliceAccountId)
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].failureReason").exists());
        // The failed incoming attempt is not shown to the destination owner.
        getAuthenticated("/api/accounts/{id}/transactions", bob.token(), bobAccount.get("id").asText())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void transferWithInsufficientFundsKeepsBalancesIntact() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        String aliceAccountId = createAccount(alice, "CHECKING").get("id").asText();
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        deposit(alice, aliceAccountId, "100").andExpect(status().isOk());

        transfer(alice, aliceAccountId, bobAccount.get("accountNumber").asText(), "100.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        transfer(alice, aliceAccountId, "000000000000", "1")
                .andExpect(status().isNotFound());
        transfer(alice, aliceAccountId, bobAccount.get("accountNumber").asText(), "0")
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(alice, aliceAccountId).decimalValue()).isEqualByComparingTo("100.00");
        assertThat(balanceOf(bob, bobAccount.get("id").asText()).decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void concurrentWithdrawalsNeverOverdrawTheAccount() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        UUID accountId = UUID.fromString(createAccount(alice, "CHECKING").get("id").asText());
        deposit(alice, accountId.toString(), "500").andExpect(status().isOk());

        List<Callable<Boolean>> withdrawals = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            withdrawals.add(() -> {
                try {
                    transactionService.withdraw(alice.id(), accountId, new AmountRequest(new BigDecimal("100"), null));
                    return true;
                } catch (InsufficientFundsException ex) {
                    return false;
                }
            });
        }

        long succeeded = runConcurrently(withdrawals).stream().filter(Boolean::booleanValue).count();

        assertThat(succeeded).isEqualTo(5);
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void concurrentOpposingTransfersPreserveTotalMoney() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        JsonNode aliceAccount = createAccount(alice, "CHECKING");
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        UUID aliceAccountId = UUID.fromString(aliceAccount.get("id").asText());
        UUID bobAccountId = UUID.fromString(bobAccount.get("id").asText());
        deposit(alice, aliceAccountId.toString(), "1000").andExpect(status().isOk());
        deposit(bob, bobAccountId.toString(), "1000").andExpect(status().isOk());

        List<Callable<Boolean>> transfers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean fromAlice = i % 2 == 0;
            transfers.add(() -> {
                if (fromAlice) {
                    transactionService.transfer(alice.id(), new TransferRequest(aliceAccountId,
                            bobAccount.get("accountNumber").asText(), new BigDecimal("15.00"), null));
                } else {
                    transactionService.transfer(bob.id(), new TransferRequest(bobAccountId,
                            aliceAccount.get("accountNumber").asText(), new BigDecimal("10.00"), null));
                }
                return true;
            });
        }

        assertThat(runConcurrently(transfers)).hasSize(20).containsOnly(true);

        BigDecimal aliceBalance = accountRepository.findById(aliceAccountId).orElseThrow().getBalance();
        BigDecimal bobBalance = accountRepository.findById(bobAccountId).orElseThrow().getBalance();
        assertThat(aliceBalance).isEqualByComparingTo("950.00");
        assertThat(bobBalance).isEqualByComparingTo("1050.00");
        assertThat(aliceBalance.add(bobBalance)).isEqualByComparingTo("2000.00");
    }

    private void freeze(String accountId) {
        var account = accountRepository.findById(UUID.fromString(accountId)).orElseThrow();
        account.changeStatus(AccountStatus.FROZEN);
        accountRepository.save(account);
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
