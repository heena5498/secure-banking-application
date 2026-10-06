package com.securebank.admin;

import com.securebank.account.AccountRepository;
import com.securebank.common.AccountNotFoundException;
import com.securebank.common.PageResponse;
import com.securebank.common.UserNotFoundException;
import com.securebank.transaction.TransactionRepository;
import com.securebank.transaction.TransactionResponse;
import com.securebank.user.UserRepository;
import com.securebank.user.UserResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-only views across all customers, for ADMIN users.
 */
@Service
@Transactional(readOnly = true)
public class AdminService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AdminService(UserRepository userRepository,
                        AccountRepository accountRepository,
                        TransactionRepository transactionRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        return PageResponse.from(userRepository.findAll(pageable).map(UserResponse::from));
    }

    public UserResponse getUser(UUID userId) {
        return userRepository.findById(userId).map(UserResponse::from).orElseThrow(UserNotFoundException::new);
    }

    public PageResponse<AdminAccountResponse> listAccounts(Pageable pageable) {
        return PageResponse.from(accountRepository.findAllBy(pageable).map(AdminAccountResponse::from));
    }

    public AdminAccountResponse getAccount(UUID accountId) {
        return accountRepository.findWithOwnerById(accountId)
                .map(AdminAccountResponse::from)
                .orElseThrow(AccountNotFoundException::new);
    }

    public PageResponse<TransactionResponse> listTransactions(Pageable pageable) {
        return PageResponse.from(transactionRepository.findAllBy(pageable).map(TransactionResponse::from));
    }

    public PageResponse<TransactionResponse> getAccountTransactions(UUID accountId, Pageable pageable) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException();
        }
        return PageResponse.from(transactionRepository.findHistory(accountId, pageable)
                .map(TransactionResponse::from));
    }
}
