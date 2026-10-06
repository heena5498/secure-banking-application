package com.securebank.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Version 1 success scenario, end to end through the HTTP API.
 */
class BankingFlowIntegrationTest extends IntegrationTestBase {

    @Test
    void versionOneSuccessScenario() throws Exception {
        // 1-2. Alice registers and logs in, receiving a JWT.
        TestUser alice = registerAndLogin("Alice");
        assertThat(alice.token()).isNotBlank();
        getAuthenticated("/api/users/me", alice.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(alice.email()))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        // 3. Alice creates a checking account.
        JsonNode aliceAccount = createAccount(alice, "CHECKING");
        String aliceAccountId = aliceAccount.get("id").asText();
        assertThat(aliceAccount.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(aliceAccount.get("balance").decimalValue()).isEqualByComparingTo("0");

        // 4. Alice deposits $1,000.
        deposit(alice, aliceAccountId, "1000.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 5. Bob registers and creates a checking account.
        TestUser bob = registerAndLogin("Bob");
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        String bobAccountId = bobAccount.get("id").asText();
        String bobAccountNumber = bobAccount.get("accountNumber").asText();

        // 6. Alice transfers $250 to Bob.
        transfer(alice, aliceAccountId, bobAccountNumber, "250.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.destinationAccountNumber").value(bobAccountNumber));

        // 7-8. Balances are updated on both sides.
        assertThat(balanceOf(alice, aliceAccountId).decimalValue()).isEqualByComparingTo("750.00");
        assertThat(balanceOf(bob, bobAccountId).decimalValue()).isEqualByComparingTo("250.00");

        // 9-10. Both transactions are recorded and Alice can retrieve her history (newest first).
        getAuthenticated("/api/accounts/{id}/transactions", alice.token(), aliceAccountId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.content[0].amount").value(250.00))
                .andExpect(jsonPath("$.content[1].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[1].amount").value(1000.00));
        getAuthenticated("/api/accounts/{id}/transactions", bob.token(), bobAccountId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("TRANSFER"));

        // 11. Alice cannot read or manipulate Bob's account.
        getAuthenticated("/api/accounts/{id}", alice.token(), bobAccountId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ACCESS_DENIED"));
        getAuthenticated("/api/accounts/{id}/transactions", alice.token(), bobAccountId)
                .andExpect(status().isForbidden());
        withdraw(alice, bobAccountId, "10.00").andExpect(status().isForbidden());
        deposit(alice, bobAccountId, "10.00").andExpect(status().isForbidden());
        transfer(alice, bobAccountId, aliceAccount.get("accountNumber").asText(), "10.00")
                .andExpect(status().isForbidden());
        getAuthenticated("/api/accounts", alice.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(aliceAccountId));

        assertThat(balanceOf(bob, bobAccountId).decimalValue()).isEqualByComparingTo("250.00");
        assertThat(balanceOf(alice, aliceAccountId).decimalValue()).isEqualByComparingTo("750.00");
    }

    @Test
    void depositAndWithdrawalValidation() throws Exception {
        TestUser carol = registerAndLogin("Carol");
        String accountId = createAccount(carol, "SAVINGS").get("id").asText();
        deposit(carol, accountId, "500").andExpect(status().isOk());

        withdraw(carol, accountId, "100").andExpect(status().isOk());
        assertThat(balanceOf(carol, accountId).decimalValue()).isEqualByComparingTo("400.00");

        deposit(carol, accountId, "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        deposit(carol, accountId, "-5").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        withdraw(carol, accountId, "-5").andExpect(status().isBadRequest());
        withdraw(carol, accountId, "400.01").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        perform(post("/api/accounts/{id}/deposit", accountId), carol.token(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));

        assertThat(balanceOf(carol, accountId).decimalValue()).isEqualByComparingTo("400.00");
    }
}
