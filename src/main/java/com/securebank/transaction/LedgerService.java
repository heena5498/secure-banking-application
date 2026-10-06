package com.securebank.transaction;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.common.AccountAccessDeniedException;
import com.securebank.common.AccountNotFoundException;
import com.securebank.common.InvalidTransferException;
import com.securebank.common.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Performs balance-changing operations. Each public method runs in a single database transaction
 * and takes row-level write locks on every account it modifies, so concurrent operations on the
 * same account are serialized and a failure at any step rolls back every change.
 */
@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public LedgerService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransactionResponse deposit(UUID userId, UUID accountId, BigDecimal requestedAmount, String description) {
        BigDecimal amount = Money.requireValidAmount(requestedAmount);
        Account account = lockOwnedAccount(userId, accountId);

        Transaction transaction = Transaction.pending(TransactionType.DEPOSIT, amount, null, account, description);
        account.credit(amount);
        transaction.markCompleted();
        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    @Transactional
    public TransactionResponse withdraw(UUID userId, UUID accountId, BigDecimal requestedAmount, String description) {
        BigDecimal amount = Money.requireValidAmount(requestedAmount);
        Account account = lockOwnedAccount(userId, accountId);

        Transaction transaction = Transaction.pending(TransactionType.WITHDRAWAL, amount, account, null, description);
        account.debit(amount);
        transaction.markCompleted();
        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    @Transactional
    public TransactionResponse transfer(UUID userId, UUID sourceAccountId, String destinationAccountNumber,
                                        BigDecimal requestedAmount, String description) {
        BigDecimal amount = Money.requireValidAmount(requestedAmount);

        // Verify ownership before revealing anything about the destination account.
        UUID ownerId = accountRepository.findOwnerIdById(sourceAccountId)
                .orElseThrow(() -> new AccountNotFoundException("Source account not found"));
        if (!ownerId.equals(userId)) {
            throw new AccountAccessDeniedException();
        }
        UUID destinationAccountId = accountRepository.findIdByAccountNumber(destinationAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found"));
        if (destinationAccountId.equals(sourceAccountId)) {
            throw new InvalidTransferException("Source and destination accounts must be different");
        }

        // Always lock in a consistent order so opposing concurrent transfers cannot deadlock.
        Account source;
        Account destination;
        if (sourceAccountId.compareTo(destinationAccountId) < 0) {
            source = lockAccount(sourceAccountId);
            destination = lockAccount(destinationAccountId);
        } else {
            destination = lockAccount(destinationAccountId);
            source = lockAccount(sourceAccountId);
        }

        source.ensureActive();
        destination.ensureActive();

        Transaction transaction = Transaction.pending(TransactionType.TRANSFER, amount, source, destination,
                description);
        source.debit(amount);
        destination.credit(amount);
        transaction.markCompleted();
        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    private Account lockOwnedAccount(UUID userId, UUID accountId) {
        Account account = lockAccount(accountId);
        if (!account.isOwnedBy(userId)) {
            throw new AccountAccessDeniedException();
        }
        return account;
    }

    private Account lockAccount(UUID accountId) {
        return accountRepository.findByIdForUpdate(accountId).orElseThrow(AccountNotFoundException::new);
    }
}
