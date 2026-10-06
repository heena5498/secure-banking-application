package com.securebank.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Transactions visible in an account's history: everything initiated from the account, plus
     * completed transactions that credited it. Failed attempts by other users to send money to the
     * account are not shown to its owner.
     */
    @Query(value = """
            select t from Transaction t
              left join fetch t.sourceAccount s
              left join fetch t.destinationAccount d
            where s.id = :accountId or (d.id = :accountId and t.status = :completed)
            order by t.createdAt desc, t.id desc
            """,
            countQuery = """
            select count(t) from Transaction t
            where t.sourceAccount.id = :accountId
               or (t.destinationAccount.id = :accountId and t.status = :completed)
            """)
    Page<Transaction> findHistory(@Param("accountId") UUID accountId,
                                  @Param("completed") TransactionStatus completed,
                                  Pageable pageable);

    default Page<Transaction> findHistory(UUID accountId, Pageable pageable) {
        return findHistory(accountId, TransactionStatus.COMPLETED, pageable);
    }

    @EntityGraph(attributePaths = {"sourceAccount", "destinationAccount"})
    Page<Transaction> findAllBy(Pageable pageable);
}
