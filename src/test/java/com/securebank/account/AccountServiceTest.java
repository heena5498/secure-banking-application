package com.securebank.account;

import com.securebank.common.AccountAccessDeniedException;
import com.securebank.common.AccountNotFoundException;
import com.securebank.user.User;
import com.securebank.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.securebank.support.TestFixtures.account;
import static com.securebank.support.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @InjectMocks
    private AccountService accountService;

    @Test
    void createsActiveAccountWithZeroBalanceForCurrentUser() {
        User alice = user("Alice");
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(accountNumberGenerator.next()).thenReturn("123456789012");
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.createAccount(alice.getId(),
                new CreateAccountRequest(AccountType.SAVINGS));

        assertThat(response.accountNumber()).isEqualTo("123456789012");
        assertThat(response.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void getAccountRejectsAccountOwnedByAnotherUser() {
        Account bobsAccount = account(user("Bob"), "100.00");
        when(accountRepository.findById(bobsAccount.getId())).thenReturn(Optional.of(bobsAccount));

        assertThatThrownBy(() -> accountService.getAccount(UUID.randomUUID(), bobsAccount.getId()))
                .isInstanceOf(AccountAccessDeniedException.class);
    }

    @Test
    void getAccountRejectsUnknownAccount() {
        UUID unknown = UUID.randomUUID();
        when(accountRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(UUID.randomUUID(), unknown))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
