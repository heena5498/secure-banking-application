package com.securebank.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.ledger.LedgerEntryRepository;
import com.securebank.support.IntegrationTestBase;
import com.securebank.transaction.Transaction;
import com.securebank.transaction.TransactionRepository;
import com.securebank.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Forces a failure after both balances have been changed in memory, proving the debit and credit
 * are rolled back together.
 */
class TransferRollbackIntegrationTest extends IntegrationTestBase {

    @MockitoSpyBean
    private TransactionRepository transactionRepository;

    @MockitoSpyBean
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void failureAfterDebitRollsBackEntireTransfer() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        String aliceAccountId = createAccount(alice, "CHECKING").get("id").asText();
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        deposit(alice, aliceAccountId, "1000").andExpect(status().isOk());
        deposit(bob, bobAccount.get("id").asText(), "500").andExpect(status().isOk());

        doThrow(new IllegalStateException("simulated database failure"))
                .when(transactionRepository)
                .save(argThat((Transaction t) -> t != null && t.getType() == TransactionType.TRANSFER));

        transfer(alice, aliceAccountId, bobAccount.get("accountNumber").asText(), "200")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("simulated"))));

        assertThat(balanceOf(alice, aliceAccountId).decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(bob, bobAccount.get("id").asText()).decimalValue()).isEqualByComparingTo("500.00");
        getAuthenticated("/api/accounts/{id}/transactions", alice.token(), aliceAccountId)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"));
    }

    @Test
    void ledgerFailureRollsBackBalancesAndTransactionRecord() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        String aliceAccountId = createAccount(alice, "CHECKING").get("id").asText();
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        deposit(alice, aliceAccountId, "1000").andExpect(status().isOk());

        doThrow(new IllegalStateException("simulated ledger failure"))
                .when(ledgerEntryRepository).saveAll(anyList());

        transfer(alice, aliceAccountId, bobAccount.get("accountNumber").asText(), "250")
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("simulated"))));
        deposit(alice, aliceAccountId, "100").andExpect(status().isInternalServerError());
        withdraw(alice, aliceAccountId, "100").andExpect(status().isInternalServerError());

        assertThat(balanceOf(alice, aliceAccountId).decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(bob, bobAccount.get("id").asText()).decimalValue()).isEqualByComparingTo("0.00");
        getAuthenticated("/api/accounts/{id}/transactions", alice.token(), aliceAccountId)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"));
    }
}
