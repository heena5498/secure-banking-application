package com.securebank.ledger;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only access to ledger entries: only inserts and reads, no update or delete methods.
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, UUID> {

    <S extends LedgerEntry> List<S> saveAll(Iterable<S> entries);

    @EntityGraph(attributePaths = "account")
    List<LedgerEntry> findByTransactionId(UUID transactionId);
}
