package com.securebank.account;

import com.securebank.common.PageResponse;
import com.securebank.common.Pagination;
import com.securebank.idempotency.IdempotentRequest;
import com.securebank.security.CurrentUser;
import com.securebank.transaction.AmountRequest;
import com.securebank.transaction.TransactionResponse;
import com.securebank.transaction.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransactionService transactionService;
    private final CurrentUser currentUser;

    public AccountController(AccountService accountService,
                             TransactionService transactionService,
                             CurrentUser currentUser) {
        this.accountService = accountService;
        this.transactionService = transactionService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse account = accountService.createAccount(currentUser.id(), request);
        return ResponseEntity.created(URI.create("/api/accounts/" + account.id())).body(account);
    }

    @GetMapping
    public List<AccountResponse> list() {
        return accountService.listAccounts(currentUser.id());
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable UUID accountId) {
        return accountService.getAccount(currentUser.id(), accountId);
    }

    @PostMapping("/{accountId}/deposit")
    public TransactionResponse deposit(@PathVariable UUID accountId,
                                       @RequestHeader(name = IdempotentRequest.HEADER, required = false)
                                       String idempotencyKey,
                                       @Valid @RequestBody AmountRequest request) {
        return transactionService.deposit(currentUser.id(), accountId, idempotencyKey, request);
    }

    @PostMapping("/{accountId}/withdraw")
    public TransactionResponse withdraw(@PathVariable UUID accountId,
                                        @RequestHeader(name = IdempotentRequest.HEADER, required = false)
                                        String idempotencyKey,
                                        @Valid @RequestBody AmountRequest request) {
        return transactionService.withdraw(currentUser.id(), accountId, idempotencyKey, request);
    }

    @GetMapping("/{accountId}/transactions")
    public PageResponse<TransactionResponse> transactions(@PathVariable UUID accountId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return transactionService.getHistory(currentUser.id(), accountId, Pagination.of(page, size));
    }
}
