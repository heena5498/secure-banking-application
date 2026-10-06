package com.securebank.transaction;

import com.securebank.account.AccountRepository;
import com.securebank.common.AccountAccessDeniedException;
import com.securebank.common.AccountNotFoundException;
import com.securebank.common.Money;
import com.securebank.common.PageResponse;
import com.securebank.common.TransactionRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entry point for financial operations. Delegates the atomic work to {@link LedgerService} and,
 * when a business rule rejects an operation on an account the caller owns, records a FAILED
 * transaction after the original database transaction has rolled back.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final LedgerService ledgerService;
    private final FailedTransactionRecorder failedTransactionRecorder;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(LedgerService ledgerService,
                              FailedTransactionRecorder failedTransactionRecorder,
                              AccountRepository accountRepository,
                              TransactionRepository transactionRepository) {
        this.ledgerService = ledgerService;
        this.failedTransactionRecorder = failedTransactionRecorder;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public TransactionResponse deposit(UUID userId, UUID accountId, AmountRequest request) {
        String description = normalize(request.description());
        try {
            return ledgerService.deposit(userId, accountId, request.amount(), description);
        } catch (TransactionRejectedException ex) {
            recordFailure(TransactionType.DEPOSIT, request.amount(), null, accountId, description, ex);
            throw ex;
        }
    }

    public TransactionResponse withdraw(UUID userId, UUID accountId, AmountRequest request) {
        String description = normalize(request.description());
        try {
            return ledgerService.withdraw(userId, accountId, request.amount(), description);
        } catch (TransactionRejectedException ex) {
            recordFailure(TransactionType.WITHDRAWAL, request.amount(), accountId, null, description, ex);
            throw ex;
        }
    }

    public TransactionResponse transfer(UUID userId, TransferRequest request) {
        String description = normalize(request.description());
        try {
            return ledgerService.transfer(userId, request.sourceAccountId(), request.destinationAccountNumber(),
                    request.amount(), description);
        } catch (TransactionRejectedException ex) {
            UUID destinationId = accountRepository.findIdByAccountNumber(request.destinationAccountNumber())
                    .orElse(null);
            recordFailure(TransactionType.TRANSFER, request.amount(), request.sourceAccountId(), destinationId,
                    description, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getHistory(UUID userId, UUID accountId, Pageable pageable) {
        UUID ownerId = accountRepository.findOwnerIdById(accountId).orElseThrow(AccountNotFoundException::new);
        if (!ownerId.equals(userId)) {
            throw new AccountAccessDeniedException();
        }
        return PageResponse.from(transactionRepository.findHistory(accountId, pageable)
                .map(TransactionResponse::from));
    }

    /**
     * Rejections are only thrown after ownership and amount validation succeed, so the caller owns
     * the account(s) involved and the amount is valid.
     */
    private void recordFailure(TransactionType type, BigDecimal amount, UUID sourceId, UUID destinationId,
                               String description, TransactionRejectedException cause) {
        try {
            failedTransactionRecorder.record(type, Money.requireValidAmount(amount), sourceId, destinationId,
                    description, cause.getMessage());
        } catch (RuntimeException recordingFailure) {
            // Never mask the original rejection; the client still receives the business error.
            log.error("Failed to record FAILED {} transaction", type, recordingFailure);
        }
    }

    private static String normalize(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }
}
