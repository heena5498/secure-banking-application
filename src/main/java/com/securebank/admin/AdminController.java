package com.securebank.admin;

import com.securebank.common.PageResponse;
import com.securebank.common.Pagination;
import com.securebank.transaction.TransactionResponse;
import com.securebank.user.UserResponse;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Access is restricted to the ADMIN role in {@link com.securebank.security.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public PageResponse<UserResponse> users(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return adminService.listUsers(Pagination.of(page, size, NEWEST_FIRST));
    }

    @GetMapping("/users/{userId}")
    public UserResponse user(@PathVariable UUID userId) {
        return adminService.getUser(userId);
    }

    @GetMapping("/accounts")
    public PageResponse<AdminAccountResponse> accounts(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return adminService.listAccounts(Pagination.of(page, size, NEWEST_FIRST));
    }

    @GetMapping("/accounts/{accountId}")
    public AdminAccountResponse account(@PathVariable UUID accountId) {
        return adminService.getAccount(accountId);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public PageResponse<TransactionResponse> accountTransactions(@PathVariable UUID accountId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return adminService.getAccountTransactions(accountId, Pagination.of(page, size));
    }

    @GetMapping("/transactions")
    public PageResponse<TransactionResponse> transactions(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return adminService.listTransactions(Pagination.of(page, size, NEWEST_FIRST));
    }
}
