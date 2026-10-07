package com.securebank.account;

import com.securebank.common.AccountAccessDeniedException;
import com.securebank.common.AccountNotFoundException;
import com.securebank.common.BankingException;
import com.securebank.common.ErrorCode;
import com.securebank.common.UserNotFoundException;
import com.securebank.user.User;
import com.securebank.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountNumberGenerator accountNumberGenerator;

    public AccountService(AccountRepository accountRepository,
                          UserRepository userRepository,
                          AccountNumberGenerator accountNumberGenerator) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.accountNumberGenerator = accountNumberGenerator;
    }

    @Transactional
    public AccountResponse createAccount(UUID userId, CreateAccountRequest request) {
        if (!request.type().isCustomerType()) {
            throw new BankingException(ErrorCode.VALIDATION_FAILED, "Account type must be CHECKING or SAVINGS");
        }
        User owner = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        Account account = new Account(accountNumberGenerator.next(), request.type(), owner);
        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(UUID userId) {
        return accountRepository.findByOwnerIdOrderByCreatedAtAsc(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID userId, UUID accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow(AccountNotFoundException::new);
        if (!account.isOwnedBy(userId)) {
            throw new AccountAccessDeniedException();
        }
        return AccountResponse.from(account);
    }
}
