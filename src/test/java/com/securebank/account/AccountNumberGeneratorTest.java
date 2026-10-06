package com.securebank.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountNumberGeneratorTest {

    @Test
    void returnsUnusedTwelveDigitAccountNumber() {
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.existsByAccountNumber(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        String accountNumber = new AccountNumberGenerator(repository).next();

        assertEquals(12, accountNumber.length());
        assertDoesNotThrow(() -> Long.parseLong(accountNumber));
    }

    @Test
    void failsAfterTenUsedCandidates() {
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.existsByAccountNumber(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> new AccountNumberGenerator(repository).next());
        verify(repository, times(10)).existsByAccountNumber(org.mockito.ArgumentMatchers.anyString());
    }
}