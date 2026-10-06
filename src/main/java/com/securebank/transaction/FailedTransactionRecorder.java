package com.securebank.transaction;

import com.securebank.account.AccountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Persists FAILED transaction records in their own database transaction, independent of the
 * rolled-back operation that produced them.
 */
@Component
public class FailedTransactionRecorder {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public FailedTransactionRecorder(AccountRepository accountRepository,
                                     TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(TransactionType type, BigDecimal amount, UUID sourceAccountId, UUID destinationAccountId,
                       String description, String failureReason) {
        transactionRepository.save(Transaction.failed(type, amount,
                sourceAccountId == null ? null : accountRepository.getReferenceById(sourceAccountId),
                destinationAccountId == null ? null : accountRepository.getReferenceById(destinationAccountId),
                description, failureReason));
    }
}
