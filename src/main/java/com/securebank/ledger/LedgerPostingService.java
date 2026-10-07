package com.securebank.ledger;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.SystemAccounts;
import com.securebank.transaction.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Writes balanced double-entry postings for completed financial transactions. Must run inside the
 * caller's database transaction so balances, the transaction record and its ledger entries commit
 * or roll back together.
 *
 * <p>The clearing account's stored balance is not updated; its position is derived from its ledger
 * entries. This keeps deposits and withdrawals from contending on a single shared row.
 */
@Service
public class LedgerPostingService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;

    public LedgerPostingService(LedgerEntryRepository ledgerEntryRepository, AccountRepository accountRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
    }

    /** External money enters the bank: debit clearing, credit the customer account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> postDeposit(Transaction transaction, Account account) {
        return post(transaction, clearingAccount(), account);
    }

    /** Money leaves the bank: debit the customer account, credit clearing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> postWithdrawal(Transaction transaction, Account account) {
        return post(transaction, account, clearingAccount());
    }

    /** Internal movement between two customer accounts; clearing is not involved. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> postTransfer(Transaction transaction, Account source, Account destination) {
        return post(transaction, source, destination);
    }

    private List<LedgerEntry> post(Transaction transaction, Account debitAccount, Account creditAccount) {
        BigDecimal amount = transaction.getAmount();
        List<LedgerEntry> entries = List.of(
                LedgerEntry.debit(transaction, debitAccount, amount),
                LedgerEntry.credit(transaction, creditAccount, amount));
        ensureBalanced(entries);
        return ledgerEntryRepository.saveAll(entries);
    }

    /**
     * Verifies that a transaction's entries are non-empty and that total debits equal total credits.
     */
    public static void ensureBalanced(List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            throw new UnbalancedLedgerException("Transaction has no ledger entries");
        }
        BigDecimal debits = total(entries, LedgerEntryType.DEBIT);
        BigDecimal credits = total(entries, LedgerEntryType.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new UnbalancedLedgerException("Ledger entries are unbalanced: debits=" + debits
                    + ", credits=" + credits);
        }
    }

    public static BigDecimal total(List<LedgerEntry> entries, LedgerEntryType type) {
        return entries.stream()
                .filter(entry -> entry.getEntryType() == type)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Account clearingAccount() {
        return accountRepository.getReferenceById(SystemAccounts.CLEARING_ACCOUNT_ID);
    }
}
