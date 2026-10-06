package com.securebank.transaction;

import com.securebank.account.AccountRepository;
import com.securebank.common.AccountAccessDeniedException;
import com.securebank.common.AccountNotActiveException;
import com.securebank.common.ErrorCode;
import com.securebank.common.InsufficientFundsException;
import com.securebank.common.InvalidAmountException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private LedgerService ledgerService;

    @Mock
    private FailedTransactionRecorder failedTransactionRecorder;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionService transactionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @Test
    void recordsFailedWithdrawalWhenFundsAreInsufficient() {
        when(ledgerService.withdraw(userId, accountId, new BigDecimal("100"), "atm"))
                .thenThrow(new InsufficientFundsException());

        assertThatThrownBy(() -> transactionService.withdraw(userId, accountId,
                new AmountRequest(new BigDecimal("100"), "  atm ")))
                .isInstanceOf(InsufficientFundsException.class);

        verify(failedTransactionRecorder).record(TransactionType.WITHDRAWAL, new BigDecimal("100.00"), accountId,
                null, "atm", "Insufficient funds");
    }

    @Test
    void recordsFailedDepositToFrozenAccount() {
        when(ledgerService.deposit(userId, accountId, BigDecimal.TEN, null))
                .thenThrow(new AccountNotActiveException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen"));

        assertThatThrownBy(() -> transactionService.deposit(userId, accountId, new AmountRequest(BigDecimal.TEN, "")))
                .isInstanceOf(AccountNotActiveException.class);

        verify(failedTransactionRecorder).record(TransactionType.DEPOSIT, new BigDecimal("10.00"), null, accountId,
                null, "Account is frozen");
    }

    @Test
    void recordsFailedTransferWithBothAccounts() {
        UUID destinationId = UUID.randomUUID();
        TransferRequest request = new TransferRequest(accountId, "123456789012", new BigDecimal("50"), null);
        when(ledgerService.transfer(userId, accountId, "123456789012", new BigDecimal("50"), null))
                .thenThrow(new InsufficientFundsException());
        when(accountRepository.findIdByAccountNumber("123456789012")).thenReturn(Optional.of(destinationId));

        assertThatThrownBy(() -> transactionService.transfer(userId, request))
                .isInstanceOf(InsufficientFundsException.class);

        verify(failedTransactionRecorder).record(TransactionType.TRANSFER, new BigDecimal("50.00"), accountId,
                destinationId, null, "Insufficient funds");
    }

    @Test
    void doesNotRecordUnauthorizedOrInvalidRequests() {
        when(ledgerService.withdraw(any(), any(), any(), any())).thenThrow(new AccountAccessDeniedException());
        when(ledgerService.deposit(any(), any(), any(), any())).thenThrow(new InvalidAmountException("bad"));

        assertThatThrownBy(() -> transactionService.withdraw(userId, accountId, new AmountRequest(BigDecimal.ONE, null)))
                .isInstanceOf(AccountAccessDeniedException.class);
        assertThatThrownBy(() -> transactionService.deposit(userId, accountId, new AmountRequest(BigDecimal.ZERO, null)))
                .isInstanceOf(InvalidAmountException.class);

        verifyNoInteractions(failedTransactionRecorder);
    }

    @Test
    void recordingFailureDoesNotMaskOriginalError() {
        when(ledgerService.withdraw(any(), any(), any(), any())).thenThrow(new InsufficientFundsException());
        doThrow(new IllegalStateException("db down")).when(failedTransactionRecorder)
                .record(any(), any(), any(), isNull(), isNull(), anyString());

        assertThatThrownBy(() -> transactionService.withdraw(userId, accountId,
                new AmountRequest(BigDecimal.ONE, null)))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void historyIsOnlyAvailableToAccountOwner() {
        when(accountRepository.findOwnerIdById(accountId)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> transactionService.getHistory(userId, accountId, PageRequest.of(0, 20)))
                .isInstanceOf(AccountAccessDeniedException.class);
        verify(transactionRepository, never()).findHistory(eq(accountId), any(PageRequest.class));
    }
}
