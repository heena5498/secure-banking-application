package com.securebank.account;

import com.securebank.common.AccountNotActiveException;
import com.securebank.common.ErrorCode;
import com.securebank.common.InsufficientFundsException;
import com.securebank.common.Timestamps;
import com.securebank.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, updatable = false, length = 20)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false, length = 20)
    private AccountType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    /** Null only for internal system accounts such as {@link AccountType#SYSTEM_CLEARING}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", updatable = false)
    private User owner;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected Account() {
    }

    public Account(String accountNumber, AccountType type, User owner) {
        this.accountNumber = accountNumber;
        this.type = type;
        this.owner = owner;
        this.balance = BigDecimal.ZERO.setScale(2);
        this.status = AccountStatus.ACTIVE;
        this.createdAt = Timestamps.now();
    }

    public boolean isOwnedBy(UUID userId) {
        return owner != null && owner.getId().equals(userId);
    }

    public void ensureActive() {
        switch (status) {
            case ACTIVE -> {
            }
            case FROZEN -> throw new AccountNotActiveException(ErrorCode.ACCOUNT_FROZEN,
                    "Account " + accountNumber + " is frozen");
            case CLOSED -> throw new AccountNotActiveException(ErrorCode.ACCOUNT_CLOSED,
                    "Account " + accountNumber + " is closed");
        }
    }

    /** Adds a validated, positive amount to the balance. */
    public void credit(BigDecimal amount) {
        ensureActive();
        balance = balance.add(amount);
    }

    /** Removes a validated, positive amount from the balance; balances can never go negative. */
    public void debit(BigDecimal amount) {
        ensureActive();
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }
        balance = balance.subtract(amount);
    }

    public void changeStatus(AccountStatus status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public AccountType getType() {
        return type;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public User getOwner() {
        return owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
