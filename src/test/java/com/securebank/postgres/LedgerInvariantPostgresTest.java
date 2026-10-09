package com.securebank.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.support.LedgerAssertions;
import com.securebank.support.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerInvariantPostgresTest extends PostgresIntegrationTestBase {

    private LedgerAssertions ledger;
    private TestUser alice;
    private UUID aliceAccountId;

    @BeforeEach
    void setUp() throws Exception {
        ledger = new LedgerAssertions(jdbcTemplate);
        alice = registerAndLogin("Alice");
        aliceAccountId = UUID.fromString(createAccount(alice, "CHECKING").get("id").asText());
    }

    @Test
    void depositDebitsClearingAndCreditsCustomer() throws Exception {
        UUID id = transactionId(deposit(alice, aliceAccountId.toString(), "500").andExpect(status().isOk()));

        ledger.assertDepositPosting(id, aliceAccountId, "500.00");
        ledger.assertLedgerMatchesBalance(aliceAccountId);
    }

    @Test
    void withdrawalDebitsCustomerAndCreditsClearing() throws Exception {
        deposit(alice, aliceAccountId.toString(), "500").andExpect(status().isOk());

        UUID id = transactionId(withdraw(alice, aliceAccountId.toString(), "100").andExpect(status().isOk()));

        ledger.assertWithdrawalPosting(id, aliceAccountId, "100.00");
        ledger.assertLedgerMatchesBalance(aliceAccountId);
    }

    @Test
    void transferDebitsSourceAndCreditsDestination() throws Exception {
        TestUser bob = registerAndLogin("Bob");
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        UUID bobAccountId = UUID.fromString(bobAccount.get("id").asText());
        deposit(alice, aliceAccountId.toString(), "1000").andExpect(status().isOk());

        UUID id = transactionId(transfer(alice, aliceAccountId.toString(), bobAccount.get("accountNumber").asText(),
                "250").andExpect(status().isCreated()));

        ledger.assertTransferPosting(id, aliceAccountId, bobAccountId, "250.00");
        ledger.assertLedgerMatchesBalance(aliceAccountId);
        ledger.assertLedgerMatchesBalance(bobAccountId);
    }

    /** Database-wide checks over every row written by any PostgreSQL test so far. */
    @Test
    void everyCompletedTransactionInTheDatabaseIsBalanced() throws Exception {
        deposit(alice, aliceAccountId.toString(), "75.50").andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from (
                  select transaction_id from ledger_entries group by transaction_id
                  having sum(case when entry_type = 'DEBIT' then amount else 0 end)
                      <> sum(case when entry_type = 'CREDIT' then amount else 0 end)) unbalanced""",
                Integer.class)).as("unbalanced transactions").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from transactions t
                where t.status = 'COMPLETED'
                  and not exists (select 1 from ledger_entries e where e.transaction_id = t.id)""",
                Integer.class)).as("completed transactions without ledger entries").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0)
                from ledger_entries""", BigDecimal.class)).isEqualByComparingTo("0");
    }

    private UUID transactionId(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return UUID.fromString(json(result).get("id").asText());
    }
}
