package com.securebank.transaction;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.common.AccountAccessDeniedException;
import com.securebank.common.AccountNotFoundException;
import com.securebank.common.InvalidTransferException;
import com.securebank.common.Money;
import com.securebank.ledger.LedgerPostingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Performs balance-changing operations. Each public method runs in a single database transaction
 * and takes row-level write locks on every account it modifies, so concurrent operations on the
 * same account are serialized and a failure at any step rolls back every change. Every completed
 * operation also writes a balanced pair of ledger entries in the same database transaction.
 */
@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerPostingService ledgerPostingService;

    public LedgerService(AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         LedgerPostingService ledgerPostingService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerPostingService = ledgerPostingService;
    }

    @Transactional
    public TransactionResponse deposit(UUID userId, UUID accountId, BigDecimal requestedAmount, String description) {
        BigDecimal amount = Money.requireValidAmount(requestedAmount);
        Account account = lockOwnedAccount(userId, accountId);

        Transaction transaction = Transaction.pending(TransactionType.DEPOSIT, amount, null, account, description);
        account.credit(amount);
        transaction.markCompleted();
        Transaction saved = transactionRepository.save(transaction);
        ledgerPostingService.postDeposit(saved, account);
        return TransactionResponse.from(saved);
    }

    @Transactional
    public TransactionResponse withdraw(UUID userId, UUID accountId, BigDecimal requestedAmount, String description) {
        BigDecimal amount = Money.requireValidAmount(requestedAmount);
        Account account = lockOwnedAccount(userId, accountId);

        Transaction transaction = Transaction.pending(TransactionType.WITHDRAWAL, amount, account, null, description);
        account.debit(amount);
        transaction.markCompleted();
        Transaction saved = transactionRepository.save(transaction);
        ledgerPostingService.postWithdrawal(saved, account);
        return TransactionResponse.from(saved);
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
        Transaction saved = transactionRepository.save(transaction);
        ledgerPostingService.postTransfer(saved, source, destination);
        return TransactionResponse.from(saved);
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
