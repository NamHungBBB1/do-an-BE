package com.doan.game.auth.repository;

import com.doan.game.auth.domain.ChildSlot;
import com.doan.game.auth.domain.LearningContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChildSlotRepository extends JpaRepository<ChildSlot, UUID> {

    Optional<ChildSlot> findByCode(String code);

    boolean existsByCode(String code);

    List<ChildSlot> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);

    /** Đếm hạn mức: slot đã lưu trữ KHÔNG tính — đó là ý nghĩa của "trả lại slot". */
    long countByOwnerIdAndContextAndArchivedFalse(UUID ownerId, LearningContext context);

    List<ChildSlot> findByOwnerIdAndContextAndArchivedFalse(UUID ownerId, LearningContext context);

    /** Các bối cảnh mà một trẻ đã liên kết tài khoản có thể vào tiếp. */
    List<ChildSlot> findByLinkedAccountIdAndArchivedFalse(UUID linkedAccountId);
}
