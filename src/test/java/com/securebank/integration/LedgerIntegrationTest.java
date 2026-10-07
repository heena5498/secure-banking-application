package com.securebank.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.securebank.account.AccountRepository;
import com.securebank.account.SystemAccounts;
import com.securebank.ledger.LedgerEntry;
import com.securebank.ledger.LedgerEntryRepository;
import com.securebank.ledger.LedgerEntryType;
import com.securebank.ledger.LedgerPostingService;
import com.securebank.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerIntegrationTest extends IntegrationTestBase {

    private static final String CLEARING = SystemAccounts.CLEARING_ACCOUNT_NUMBER;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void depositCreatesClearingDebitAndCustomerCredit() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        JsonNode account = createAccount(alice, "CHECKING");

        String txId = json(deposit(alice, account.get("id").asText(), "500").andExpect(status().isOk()))
                .get("id").asText();

        assertEntries(txId,
                tuple(CLEARING, LedgerEntryType.DEBIT, new BigDecimal("500.00")),
                tuple(account.get("accountNumber").asText(), LedgerEntryType.CREDIT, new BigDecimal("500.00")));
        assertThat(balanceOf(alice, account.get("id").asText()).decimalValue()).isEqualByComparingTo("500.00");
    }

    @Test
    void withdrawalCreatesCustomerDebitAndClearingCredit() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        JsonNode account = createAccount(alice, "CHECKING");
        deposit(alice, account.get("id").asText(), "500").andExpect(status().isOk());

        String txId = json(withdraw(alice, account.get("id").asText(), "100").andExpect(status().isOk()))
                .get("id").asText();

        assertEntries(txId,
                tuple(account.get("accountNumber").asText(), LedgerEntryType.DEBIT, new BigDecimal("100.00")),
                tuple(CLEARING, LedgerEntryType.CREDIT, new BigDecimal("100.00")));
        assertThat(balanceOf(alice, account.get("id").asText()).decimalValue()).isEqualByComparingTo("400.00");
    }

    @Test
    void transferCreatesSourceDebitAndDestinationCreditWithoutClearing() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        JsonNode aliceAccount = createAccount(alice, "CHECKING");
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        deposit(alice, aliceAccount.get("id").asText(), "1000").andExpect(status().isOk());

        String txId = json(transfer(alice, aliceAccount.get("id").asText(),
                bobAccount.get("accountNumber").asText(), "250").andExpect(status().isCreated()))
                .get("id").asText();

        assertEntries(txId,
                tuple(aliceAccount.get("accountNumber").asText(), LedgerEntryType.DEBIT, new BigDecimal("250.00")),
                tuple(bobAccount.get("accountNumber").asText(), LedgerEntryType.CREDIT, new BigDecimal("250.00")));
        assertThat(balanceOf(alice, aliceAccount.get("id").asText()).decimalValue()).isEqualByComparingTo("750.00");
        assertThat(balanceOf(bob, bobAccount.get("id").asText()).decimalValue()).isEqualByComparingTo("250.00");
    }

    /** The V2.1 Definition of Done scenario. */
    @Test
    void depositTransferWithdrawalScenarioKeepsEveryTransactionBalanced() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        TestUser bob = registerAndLogin("Bob");
        JsonNode aliceAccount = createAccount(alice, "CHECKING");
        JsonNode bobAccount = createAccount(bob, "CHECKING");
        String aliceId = aliceAccount.get("id").asText();
        String bobId = bobAccount.get("id").asText();
        String aliceNumber = aliceAccount.get("accountNumber").asText();
        String bobNumber = bobAccount.get("accountNumber").asText();
        assertThat(balanceOf(alice, aliceId).decimalValue()).isEqualByComparingTo("0.00");

        String depositId = json(deposit(alice, aliceId, "1000").andExpect(status().isOk())).get("id").asText();
        assertEntries(depositId,
                tuple(CLEARING, LedgerEntryType.DEBIT, new BigDecimal("1000.00")),
                tuple(aliceNumber, LedgerEntryType.CREDIT, new BigDecimal("1000.00")));
        assertThat(balanceOf(alice, aliceId).decimalValue()).isEqualByComparingTo("1000.00");

        String transferId = json(transfer(alice, aliceId, bobNumber, "250").andExpect(status().isCreated()))
                .get("id").asText();
        assertEntries(transferId,
                tuple(aliceNumber, LedgerEntryType.DEBIT, new BigDecimal("250.00")),
                tuple(bobNumber, LedgerEntryType.CREDIT, new BigDecimal("250.00")));
        assertThat(balanceOf(alice, aliceId).decimalValue()).isEqualByComparingTo("750.00");
        assertThat(balanceOf(bob, bobId).decimalValue()).isEqualByComparingTo("250.00");

        String withdrawalId = json(withdraw(bob, bobId, "50").andExpect(status().isOk())).get("id").asText();
        assertEntries(withdrawalId,
                tuple(bobNumber, LedgerEntryType.DEBIT, new BigDecimal("50.00")),
                tuple(CLEARING, LedgerEntryType.CREDIT, new BigDecimal("50.00")));
        assertThat(balanceOf(bob, bobId).decimalValue()).isEqualByComparingTo("200.00");

        // Each customer account's ledger position (credits - debits) matches its stored balance.
        assertThat(ledgerPosition(aliceId)).isEqualByComparingTo("750.00");
        assertThat(ledgerPosition(bobId)).isEqualByComparingTo("200.00");
        // Across the whole ledger, debits and credits always net to zero.
        assertThat(jdbcTemplate.queryForObject("""
                select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0)
                from ledger_entries""", BigDecimal.class)).isEqualByComparingTo("0");
    }

    @Test
    void rejectedOperationsCreateNoLedgerEntries() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        JsonNode account = createAccount(alice, "CHECKING");
        deposit(alice, account.get("id").asText(), "100").andExpect(status().isOk());

        withdraw(alice, account.get("id").asText(), "500").andExpect(status().isUnprocessableEntity());

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from ledger_entries e join transactions t on t.id = e.transaction_id
                where t.status <> 'COMPLETED'""", Integer.class)).isZero();
        assertThat(ledgerPosition(account.get("id").asText())).isEqualByComparingTo("100.00");
    }

    @Test
    void clearingAccountIsHiddenFromCustomers() throws Exception {
        TestUser alice = registerAndLogin("Alice");
        JsonNode account = createAccount(alice, "CHECKING");
        deposit(alice, account.get("id").asText(), "100").andExpect(status().isOk());
        UUID clearingId = SystemAccounts.CLEARING_ACCOUNT_ID;

        assertThat(accountRepository.findById(clearingId)).isPresent();
        getAuthenticated("/api/accounts", alice.token())
                .andExpect(jsonPath("$.length()").value(1));
        getAuthenticated("/api/accounts/{id}", alice.token(), clearingId).andExpect(status().isForbidden());
        getAuthenticated("/api/accounts/{id}/transactions", alice.token(), clearingId)
                .andExpect(status().isNotFound());
        withdraw(alice, clearingId.toString(), "1").andExpect(status().isForbidden());
        transfer(alice, account.get("id").asText(), CLEARING, "1").andExpect(status().isBadRequest());
        perform(post("/api/accounts"), alice.token(), Map.of("type", "SYSTEM_CLEARING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(balanceOf(alice, account.get("id").asText()).decimalValue()).isEqualByComparingTo("100.00");
    }

    private void assertEntries(String transactionId, org.assertj.core.groups.Tuple... expected) {
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(UUID.fromString(transactionId));
        assertThat(entries)
                .extracting(e -> e.getAccount().getAccountNumber(), LedgerEntry::getEntryType, LedgerEntry::getAmount)
                .containsExactlyInAnyOrder(expected);
        assertThat(LedgerPostingService.total(entries, LedgerEntryType.DEBIT))
                .isEqualByComparingTo(LedgerPostingService.total(entries, LedgerEntryType.CREDIT));
    }

    private BigDecimal ledgerPosition(String accountId) {
        return jdbcTemplate.queryForObject("""
                select coalesce(sum(case when entry_type = 'CREDIT' then amount else -amount end), 0)
                from ledger_entries where account_id = ?""", BigDecimal.class, UUID.fromString(accountId));
    }
}
