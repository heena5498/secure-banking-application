package com.securebank.transaction;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.AccountStatus;
import com.securebank.common.AccountAccessDeniedException;
import com.securebank.common.AccountNotActiveException;
import com.securebank.common.AccountNotFoundException;
import com.securebank.common.ErrorCode;
import com.securebank.common.InsufficientFundsException;
import com.securebank.common.InvalidAmountException;
import com.securebank.common.InvalidTransferException;
import com.securebank.ledger.LedgerPostingService;
import com.securebank.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.securebank.support.TestFixtures.account;
import static com.securebank.support.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    private LedgerService ledger;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(accountRepository, transactionRepository, ledgerPostingService);
        alice = user("Alice");
        bob = user("Bob");
        lenient().when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubLock(Account account) {
        lenient().when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    }

    @Nested
    class Deposits {

        @Test
        void increasesBalanceAndRecordsCompletedTransaction() {
            Account account = account(alice, "500.00");
            stubLock(account);

            TransactionResponse result = ledger.deposit(alice.getId(), account.getId(), new BigDecimal("200"), "pay");

            assertThat(account.getBalance()).isEqualByComparingTo("700.00");
            assertThat(result.type()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(result.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(result.amount()).isEqualTo(new BigDecimal("200.00"));
            assertThat(result.destinationAccountNumber()).isEqualTo(account.getAccountNumber());
            assertThat(result.sourceAccountNumber()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "-1", "-200.50", "10.001"})
        void rejectsInvalidAmountsBeforeTouchingAccounts(String amount) {
            assertThatThrownBy(() -> ledger.deposit(alice.getId(), UUID.randomUUID(), new BigDecimal(amount), null))
                    .isInstanceOf(InvalidAmountException.class);
            verifyNoInteractions(accountRepository, transactionRepository, ledgerPostingService);
        }

        @ParameterizedTest
        @EnumSource(value = AccountStatus.class, names = {"FROZEN", "CLOSED"})
        void rejectsInactiveAccounts(AccountStatus status) {
            Account account = account(alice, "500.00", status);
            stubLock(account);

            assertThatThrownBy(() -> ledger.deposit(alice.getId(), account.getId(), new BigDecimal("50"), null))
                    .isInstanceOf(AccountNotActiveException.class)
                    .extracting("errorCode")
                    .isEqualTo(status == AccountStatus.FROZEN ? ErrorCode.ACCOUNT_FROZEN : ErrorCode.ACCOUNT_CLOSED);
            assertThat(account.getBalance()).isEqualByComparingTo("500.00");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        void rejectsAccountOwnedByAnotherUser() {
            Account bobsAccount = account(bob, "500.00");
            stubLock(bobsAccount);

            assertThatThrownBy(() -> ledger.deposit(alice.getId(), bobsAccount.getId(), new BigDecimal("50"), null))
                    .isInstanceOf(AccountAccessDeniedException.class);
            assertThat(bobsAccount.getBalance()).isEqualByComparingTo("500.00");
        }

        @Test
        void rejectsUnknownAccount() {
            UUID unknown = UUID.randomUUID();
            when(accountRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ledger.deposit(alice.getId(), unknown, new BigDecimal("50"), null))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    class Withdrawals {

        @Test
        void decreasesBalanceAndRecordsCompletedTransaction() {
            Account account = account(alice, "500.00");
            stubLock(account);

            TransactionResponse result = ledger.withdraw(alice.getId(), account.getId(), new BigDecimal("100"), null);

            assertThat(account.getBalance()).isEqualByComparingTo("400.00");
            assertThat(result.type()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(result.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(result.sourceAccountNumber()).isEqualTo(account.getAccountNumber());
            assertThat(result.destinationAccountNumber()).isNull();
        }

        @Test
        void allowsWithdrawingEntireBalance() {
            Account account = account(alice, "500.00");
            stubLock(account);

            ledger.withdraw(alice.getId(), account.getId(), new BigDecimal("500.00"), null);

            assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        void rejectsWithdrawalExceedingBalance() {
            Account account = account(alice, "500.00");
            stubLock(account);

            assertThatThrownBy(() -> ledger.withdraw(alice.getId(), account.getId(), new BigDecimal("500.01"), null))
                    .isInstanceOf(InsufficientFundsException.class);
            assertThat(account.getBalance()).isEqualByComparingTo("500.00");
            verify(transactionRepository, never()).save(any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-100"})
        void rejectsZeroAndNegativeAmounts(String amount) {
            assertThatThrownBy(() -> ledger.withdraw(alice.getId(), UUID.randomUUID(), new BigDecimal(amount), null))
                    .isInstanceOf(InvalidAmountException.class);
            verifyNoInteractions(accountRepository, transactionRepository);
        }

        @ParameterizedTest
        @EnumSource(value = AccountStatus.class, names = {"FROZEN", "CLOSED"})
        void rejectsInactiveAccounts(AccountStatus status) {
            Account account = account(alice, "500.00", status);
            stubLock(account);

            assertThatThrownBy(() -> ledger.withdraw(alice.getId(), account.getId(), new BigDecimal("50"), null))
                    .isInstanceOf(AccountNotActiveException.class);
            assertThat(account.getBalance()).isEqualByComparingTo("500.00");
        }

        @Test
        void rejectsAccountOwnedByAnotherUser() {
            Account bobsAccount = account(bob, "500.00");
            stubLock(bobsAccount);

            assertThatThrownBy(() -> ledger.withdraw(alice.getId(), bobsAccount.getId(), new BigDecimal("50"), null))
                    .isInstanceOf(AccountAccessDeniedException.class);
            assertThat(bobsAccount.getBalance()).isEqualByComparingTo("500.00");
            verify(transactionRepository, never()).save(any());
        }
    }

    @Nested
    class Transfers {

        private Account aliceAccount;
        private Account bobAccount;

        @BeforeEach
        void setUpAccounts() {
            aliceAccount = account(alice, "1000.00");
            bobAccount = account(bob, "500.00");
        }

        private void stubTransfer() {
            lenient().when(accountRepository.findOwnerIdById(aliceAccount.getId()))
                    .thenReturn(Optional.of(alice.getId()));
            lenient().when(accountRepository.findIdByAccountNumber(bobAccount.getAccountNumber()))
                    .thenReturn(Optional.of(bobAccount.getId()));
            stubLock(aliceAccount);
            stubLock(bobAccount);
        }

        private TransactionResponse transfer(String amount) {
            return ledger.transfer(alice.getId(), aliceAccount.getId(), bobAccount.getAccountNumber(),
                    new BigDecimal(amount), "rent");
        }

        @Test
        void movesMoneyBetweenAccountsAndRecordsOneTransaction() {
            stubTransfer();

            TransactionResponse result = transfer("200");

            assertThat(aliceAccount.getBalance()).isEqualByComparingTo("800.00");
            assertThat(bobAccount.getBalance()).isEqualByComparingTo("700.00");
            assertThat(result.type()).isEqualTo(TransactionType.TRANSFER);
            assertThat(result.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(result.sourceAccountNumber()).isEqualTo(aliceAccount.getAccountNumber());
            assertThat(result.destinationAccountNumber()).isEqualTo(bobAccount.getAccountNumber());

            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(saved.capture());
            assertThat(saved.getValue().getAmount()).isEqualTo(new BigDecimal("200.00"));
            assertThat(saved.getValue().getDescription()).isEqualTo("rent");
        }

        @Test
        void rejectsInsufficientFundsWithoutChangingEitherBalance() {
            stubTransfer();

            assertThatThrownBy(() -> transfer("1000.01")).isInstanceOf(InsufficientFundsException.class);

            assertThat(aliceAccount.getBalance()).isEqualByComparingTo("1000.00");
            assertThat(bobAccount.getBalance()).isEqualByComparingTo("500.00");
            verify(transactionRepository, never()).save(any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-200", "0.001"})
        void rejectsInvalidAmounts(String amount) {
            assertThatThrownBy(() -> transfer(amount)).isInstanceOf(InvalidAmountException.class);
            verifyNoInteractions(accountRepository, transactionRepository);
        }

        @Test
        void rejectsSourceAccountNotOwnedByCaller() {
            when(accountRepository.findOwnerIdById(bobAccount.getId())).thenReturn(Optional.of(bob.getId()));

            assertThatThrownBy(() -> ledger.transfer(alice.getId(), bobAccount.getId(),
                    aliceAccount.getAccountNumber(), new BigDecimal("100"), null))
                    .isInstanceOf(AccountAccessDeniedException.class);

            verify(accountRepository, never()).findByIdForUpdate(any());
            verify(accountRepository, never()).findIdByAccountNumber(any());
            assertThat(bobAccount.getBalance()).isEqualByComparingTo("500.00");
        }

        @ParameterizedTest
        @EnumSource(value = AccountStatus.class, names = {"FROZEN", "CLOSED"})
        void rejectsInactiveSourceAccount(AccountStatus status) {
            aliceAccount.changeStatus(status);
            stubTransfer();

            assertThatThrownBy(() -> transfer("100")).isInstanceOf(AccountNotActiveException.class);
            assertThat(aliceAccount.getBalance()).isEqualByComparingTo("1000.00");
            assertThat(bobAccount.getBalance()).isEqualByComparingTo("500.00");
        }

        @ParameterizedTest
        @EnumSource(value = AccountStatus.class, names = {"FROZEN", "CLOSED"})
        void rejectsInactiveDestinationAccount(AccountStatus status) {
            bobAccount.changeStatus(status);
            stubTransfer();

            assertThatThrownBy(() -> transfer("100")).isInstanceOf(AccountNotActiveException.class);
            assertThat(aliceAccount.getBalance()).isEqualByComparingTo("1000.00");
            assertThat(bobAccount.getBalance()).isEqualByComparingTo("500.00");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        void rejectsTransferToSameAccount() {
            when(accountRepository.findOwnerIdById(aliceAccount.getId())).thenReturn(Optional.of(alice.getId()));
            when(accountRepository.findIdByAccountNumber(aliceAccount.getAccountNumber()))
                    .thenReturn(Optional.of(aliceAccount.getId()));

            assertThatThrownBy(() -> ledger.transfer(alice.getId(), aliceAccount.getId(),
                    aliceAccount.getAccountNumber(), new BigDecimal("10"), null))
                    .isInstanceOf(InvalidTransferException.class);
        }

        @Test
        void rejectsUnknownDestination() {
            when(accountRepository.findOwnerIdById(aliceAccount.getId())).thenReturn(Optional.of(alice.getId()));
            when(accountRepository.findIdByAccountNumber("999999999999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> ledger.transfer(alice.getId(), aliceAccount.getId(), "999999999999",
                    new BigDecimal("10"), null))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThat(aliceAccount.getBalance()).isEqualByComparingTo("1000.00");
        }

        @Test
        void locksAccountsInIdOrderRegardlessOfDirection() {
            stubTransfer();
            when(accountRepository.findOwnerIdById(bobAccount.getId())).thenReturn(Optional.of(bob.getId()));
            when(accountRepository.findIdByAccountNumber(aliceAccount.getAccountNumber()))
                    .thenReturn(Optional.of(aliceAccount.getId()));
            UUID first = aliceAccount.getId().compareTo(bobAccount.getId()) < 0 ? aliceAccount.getId() : bobAccount.getId();
            UUID second = first.equals(aliceAccount.getId()) ? bobAccount.getId() : aliceAccount.getId();

            transfer("10");
            ledger.transfer(bob.getId(), bobAccount.getId(), aliceAccount.getAccountNumber(), new BigDecimal("10"), null);

            InOrder order = inOrder(accountRepository);
            order.verify(accountRepository).findByIdForUpdate(first);
            order.verify(accountRepository).findByIdForUpdate(second);
            order.verify(accountRepository).findByIdForUpdate(first);
            order.verify(accountRepository).findByIdForUpdate(second);
        }
    }
}
