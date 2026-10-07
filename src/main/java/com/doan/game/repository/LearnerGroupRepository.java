package com.doan.game.repository;

import com.doan.game.entity.LearnerGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LearnerGroupRepository extends JpaRepository<LearnerGroup, UUID> {

    /**
     * Khoá dòng nhóm (SELECT ... FOR UPDATE) khi mở slot: đếm slot rồi chèn mà không khoá thì
     * hai request song song cùng đếm 3/4 rồi cùng chèn, nhóm thành 5/4. Mẫu có sẵn:
     * TransactionRepository.lockByOrderCode. Hưng chốt 07/10.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from LearnerGroup g where g.id = :id")
    Optional<LearnerGroup> lockById(@Param("id") UUID id);
}
