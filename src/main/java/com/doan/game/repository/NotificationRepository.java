package com.doan.game.repository;

import com.doan.game.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Chuông: mọi thông báo của tôi, mới nhất trước (sort truyền từ service: createdAt DESC, id DESC). */
    Page<Notification> findByRecipient_Id(UUID recipientId, Pageable pageable);

    Page<Notification> findByRecipient_IdAndReadAtIsNull(UUID recipientId, Pageable pageable);

    long countByRecipient_IdAndReadAtIsNull(UUID recipientId);

    /** Đọc / đánh dấu đã đọc: lọc luôn người nhận trong câu — không có cửa đọc hộ người khác. */
    Optional<Notification> findByIdAndRecipient_Id(UUID id, UUID recipientId);

    /** Chống trùng: retry cùng sự kiện thì đã có dòng rồi, không insert thêm. */
    Optional<Notification> findByEventKey(String eventKey);

    /**
     * Đọc tất cả: một câu UPDATE, chỉ đụng dòng CHƯA đọc của chính tôi. Trả số dòng đã đổi để FE
     * hiện "đã đánh dấu N thông báo".
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now "
            + "where n.recipient.id = :recipientId and n.readAt is null")
    int markAllRead(@Param("recipientId") UUID recipientId, @Param("now") Instant now);
}
