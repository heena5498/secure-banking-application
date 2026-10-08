package com.securebank.idempotency;

import com.securebank.common.BankingException;
import com.securebank.common.ErrorCode;
import com.securebank.transaction.Transaction;
import com.securebank.transaction.TransactionRepository;
import com.securebank.transaction.TransactionResponse;
import com.securebank.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes financial operations safely retryable.
 *
 * <p>{@link #execute} inserts the idempotency record <em>before</em> the operation runs and in the
 * same database transaction. The UNIQUE (user_id, idempotency_key) constraint means only one
 * request can claim a key: a concurrent duplicate blocks on the unique index until the first
 * transaction finishes, then fails with a constraint violation and can replay the committed result.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository recordRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public IdempotencyService(IdempotencyRecordRepository recordRepository,
                              TransactionRepository transactionRepository,
                              UserRepository userRepository) {
        this.recordRepository = recordRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    /**
     * Returns the original result if this user already completed a request with this key, or empty
     * if the key is unused.
     *
     * @throws BankingException IDEMPOTENCY_CONFLICT if the key was used for a different request
     */
    @Transactional(readOnly = true)
    public Optional<TransactionResponse> findPreviousResult(IdempotentRequest request) {
        return recordRepository.findByUserIdAndIdempotencyKey(request.userId(), request.key())
                .map(record -> {
                    if (!record.matches(request)) {
                        throw new BankingException(ErrorCode.IDEMPOTENCY_CONFLICT,
                                "Idempotency key has already been used for a different request");
                    }
                    Transaction transaction = transactionRepository
                            .findWithAccountsById(record.getTransaction().getId())
                            .orElseThrow(() -> new IllegalStateException("Idempotent transaction is missing"));
                    return TransactionResponse.from(transaction);
                });
    }

    /**
     * Claims the key, runs the operation and links its transaction to the record, all in one
     * database transaction. If the operation fails, the claim is rolled back with it.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if another request has
     *                                                                 already claimed the key
     */
    @Transactional
    public TransactionResponse execute(IdempotentRequest request, Supplier<TransactionResponse> operation) {
        IdempotencyRecord record = recordRepository.saveAndFlush(
                IdempotencyRecord.claim(userRepository.getReferenceById(request.userId()), request));
        TransactionResponse result = operation.get();
        record.complete(transactionRepository.getReferenceById(result.id()));
        return result;
    }
}
