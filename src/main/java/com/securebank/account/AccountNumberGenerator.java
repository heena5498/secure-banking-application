package com.securebank.account;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates random, non-sequential 12-digit account numbers.
 */
@Component
public class AccountNumberGenerator {

    private static final long UPPER_BOUND = 1_000_000_000_000L;
    private static final int MAX_ATTEMPTS = 10;

    private final SecureRandom random = new SecureRandom();
    private final AccountRepository accountRepository;

    public AccountNumberGenerator(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public String next() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = String.format("%012d", random.nextLong(UPPER_BOUND));
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique account number");
    }
}
