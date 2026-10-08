package com.securebank.transaction;

import com.securebank.idempotency.IdempotentRequest;
import com.securebank.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransactionService transactionService;
    private final CurrentUser currentUser;

    public TransferController(TransactionService transactionService, CurrentUser currentUser) {
        this.transactionService = transactionService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse transfer(@RequestHeader(name = IdempotentRequest.HEADER, required = false)
                                        String idempotencyKey,
                                        @Valid @RequestBody TransferRequest request) {
        return transactionService.transfer(currentUser.id(), idempotencyKey, request);
    }
}
