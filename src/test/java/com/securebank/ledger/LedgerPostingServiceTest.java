package com.securebank.ledger;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.SystemAccounts;
import com.securebank.transaction.Transaction;
import com.securebank.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static com.securebank.support.TestFixtures.account;
import static com.securebank.support.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LedgerPostingServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountRepository accountRepository;

    private LedgerPostingService postingService;
    private Account clearing;
    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        postingService = new LedgerPostingService(ledgerEntryRepository, accountRepository);
        clearing = account(user("Clearing"), "0.00");
        alice = account(user("Alice"), "0.00");
        bob = account(user("Bob"), "0.00");
        lenient().when(accountRepository.getReferenceById(SystemAccounts.CLEARING_ACCOUNT_ID)).thenReturn(clearing);
        lenient().when(ledgerEntryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Transaction completed(TransactionType type, String amount) {
        Transaction transaction = Transaction.pending(type, new BigDecimal(amount), null, null, null);
        transaction.markCompleted();
        return transaction;
    }

    @Test
    void depositDebitsClearingAndCreditsCustomer() {
        List<LedgerEntry> entries = postingService.postDeposit(completed(TransactionType.DEPOSIT, "500.00"), alice);

        assertThat(entries).extracting(LedgerEntry::getAccount, LedgerEntry::getEntryType, LedgerEntry::getAmount)
                .containsExactly(
                        tuple(clearing, LedgerEntryType.DEBIT, new BigDecimal("500.00")),
                        tuple(alice, LedgerEntryType.CREDIT, new BigDecimal("500.00")));
    }

    @Test
    void withdrawalDebitsCustomerAndCreditsClearing() {
        List<LedgerEntry> entries = postingService.postWithdrawal(
                completed(TransactionType.WITHDRAWAL, "100.00"), alice);

        assertThat(entries).extracting(LedgerEntry::getAccount, LedgerEntry::getEntryType)
                .containsExactly(tuple(alice, LedgerEntryType.DEBIT), tuple(clearing, LedgerEntryType.CREDIT));
    }

    @Test
    void transferDebitsSourceAndCreditsDestinationWithoutClearing() {
        List<LedgerEntry> entries = postingService.postTransfer(
                completed(TransactionType.TRANSFER, "250.00"), alice, bob);

        assertThat(entries).extracting(LedgerEntry::getAccount, LedgerEntry::getEntryType)
                .containsExactly(tuple(alice, LedgerEntryType.DEBIT), tuple(bob, LedgerEntryType.CREDIT));
        verify(accountRepository, never()).getReferenceById(SystemAccounts.CLEARING_ACCOUNT_ID);
    }

    @Test
    void balancedEntriesPassValidation() {
        Transaction transaction = completed(TransactionType.TRANSFER, "250.00");
        List<LedgerEntry> entries = List.of(
                LedgerEntry.debit(transaction, alice, new BigDecimal("250.00")),
                LedgerEntry.credit(transaction, bob, new BigDecimal("250")));

        assertThatCode(() -> LedgerPostingService.ensureBalanced(entries)).doesNotThrowAnyException();
    }

    @Test
    void unbalancedEntriesFailValidation() {
        Transaction transaction = completed(TransactionType.TRANSFER, "250.00");
        List<LedgerEntry> entries = List.of(
                LedgerEntry.debit(transaction, alice, new BigDecimal("250.00")),
                LedgerEntry.credit(transaction, bob, new BigDecimal("200.00")));

        assertThatThrownBy(() -> LedgerPostingService.ensureBalanced(entries))
                .isInstanceOf(UnbalancedLedgerException.class)
                .hasMessageContaining("debits=250.00")
                .hasMessageContaining("credits=200.00");
    }

    @Test
    void emptyEntriesFailValidation() {
        assertThatThrownBy(() -> LedgerPostingService.ensureBalanced(List.of()))
                .isInstanceOf(UnbalancedLedgerException.class);
    }
}
