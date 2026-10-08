package com.securebank.transaction;

import com.securebank.account.AccountRepository;
import com.securebank.common.AccountAccessDeniedException;
import com.securebank.common.AccountNotFoundException;
import com.securebank.common.Money;
import com.securebank.common.PageResponse;
import com.securebank.common.TransactionRejectedException;
import com.securebank.idempotency.IdempotencyService;
import com.securebank.idempotency.IdempotentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Entry point for financial operations. Every operation requires an idempotency key: a retried
 * request returns the original result instead of moving money again. The atomic work is delegated
 * to {@link LedgerService}, wrapped by {@link IdempotencyService} so the key claim commits or rolls
 * back with it. When a business rule rejects an operation on an account the caller owns, a FAILED
 * transaction is recorded after the original database transaction has rolled back; rejected
 * requests do not consume their key.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final LedgerService ledgerService;
    private final FailedTransactionRecorder failedTransactionRecorder;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyService idempotencyService;

    public TransactionService(LedgerService ledgerService,
                              FailedTransactionRecorder failedTransactionRecorder,
                              AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              IdempotencyService idempotencyService) {
        this.ledgerService = ledgerService;
        this.failedTransactionRecorder = failedTransactionRecorder;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyService = idempotencyService;
    }

    public TransactionResponse deposit(UUID userId, UUID accountId, String idempotencyKey, AmountRequest request) {
        String description = normalize(request.description());
        IdempotentRequest idempotent = IdempotentRequest.of(userId, idempotencyKey, TransactionType.DEPOSIT,
                accountId, request.amount(), description);
        return executeIdempotently(idempotent,
                () -> ledgerService.deposit(userId, accountId, request.amount(), description),
                ex -> recordFailure(TransactionType.DEPOSIT, request.amount(), null, accountId, description, ex));
    }

    public TransactionResponse withdraw(UUID userId, UUID accountId, String idempotencyKey, AmountRequest request) {
        String description = normalize(request.description());
        IdempotentRequest idempotent = IdempotentRequest.of(userId, idempotencyKey, TransactionType.WITHDRAWAL,
                accountId, request.amount(), description);
        return executeIdempotently(idempotent,
                () -> ledgerService.withdraw(userId, accountId, request.amount(), description),
                ex -> recordFailure(TransactionType.WITHDRAWAL, request.amount(), accountId, null, description, ex));
    }

    public TransactionResponse transfer(UUID userId, String idempotencyKey, TransferRequest request) {
        String description = normalize(request.description());
        IdempotentRequest idempotent = IdempotentRequest.of(userId, idempotencyKey, TransactionType.TRANSFER,
                request.sourceAccountId(), request.destinationAccountNumber(), request.amount(), description);
        return executeIdempotently(idempotent,
                () -> ledgerService.transfer(userId, request.sourceAccountId(), request.destinationAccountNumber(),
                        request.amount(), description),
                ex -> {
                    UUID destinationId = accountRepository.findIdByAccountNumber(request.destinationAccountNumber())
                            .orElse(null);
                    recordFailure(TransactionType.TRANSFER, request.amount(), request.sourceAccountId(),
                            destinationId, description, ex);
                });
    }

    private TransactionResponse executeIdempotently(IdempotentRequest idempotent,
                                                    Supplier<TransactionResponse> operation,
                                                    Consumer<TransactionRejectedException> onRejected) {
        Optional<TransactionResponse> previous = idempotencyService.findPreviousResult(idempotent);
        if (previous.isPresent()) {
            return previous.get();
        }
        try {
            return idempotencyService.execute(idempotent, operation);
        } catch (TransactionRejectedException ex) {
            onRejected.accept(ex);
            throw ex;
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request claimed the same key first and committed. This request's database
            // transaction rolled back before moving any money, so return the original result.
            log.debug("Idempotency key already claimed by a concurrent {} request", idempotent.operationType());
            return idempotencyService.findPreviousResult(idempotent).orElseThrow(() -> ex);
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
