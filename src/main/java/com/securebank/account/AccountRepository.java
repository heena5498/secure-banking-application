package com.securebank.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** Loads the account and takes a row-level write lock (SELECT ... FOR UPDATE) until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    @Query("select a.owner.id from Account a where a.id = :id")
    Optional<UUID> findOwnerIdById(@Param("id") UUID id);

    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<UUID> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    List<Account> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);

    boolean existsByAccountNumber(String accountNumber);

    @EntityGraph(attributePaths = "owner")
    Page<Account> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Optional<Account> findWithOwnerById(UUID id);
}
