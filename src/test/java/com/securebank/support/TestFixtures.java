package com.securebank.support;

import com.securebank.account.Account;
import com.securebank.account.AccountStatus;
import com.securebank.account.AccountType;
import com.securebank.user.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class TestFixtures {

    private static final AtomicLong ACCOUNT_NUMBERS = new AtomicLong(100_000_000_000L);

    private TestFixtures() {
    }

    public static User user(String firstName) {
        User user = new User(firstName, "Tester", firstName.toLowerCase() + "@example.com", "{bcrypt}hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    public static Account account(User owner, String balance) {
        return account(owner, balance, AccountStatus.ACTIVE);
    }

    public static Account account(User owner, String balance, AccountStatus status) {
        Account account = new Account(String.valueOf(ACCOUNT_NUMBERS.incrementAndGet()), AccountType.CHECKING, owner);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(account, "balance", new BigDecimal(balance));
        account.changeStatus(status);
        return account;
    }
}
