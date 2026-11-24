package com.doan.game.repository;

import com.doan.game.entity.AiJob;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AiJobRepository extends JpaRepository<AiJob, UUID> {

    /**
     * Việc tới lượt: QUEUED hoặc WAITING_RETRY, đã qua nextAttemptAt, cũ nhất trước. Khoá ghi + lock.timeout = -2 (SKIP_LOCKED): Hibernate
     * dịch theo dialect — Postgres ra "… SKIP LOCKED" (hai máy / hai luồng không lấy trùng việc),
     * dialect không hỗ trợ SKIP LOCKED thì rơi về khoá thường. Viết JPQL thay vì native để H2 trong test chạy cùng câu.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from AiJob j where j.status in ('QUEUED', 'WAITING_RETRY') "
            + "and (j.nextAttemptAt is null or j.nextAttemptAt <= :now) "
            + "order by j.createdAt asc, j.id asc")
    List<AiJob> lockDue(@Param("now") Instant now, Pageable page);

    /**
     * Gom lô (25/11): việc tới lượt của MỘT ngăn, chưa lỗi lần nào (attempts 0 / null), cũ nhất trước; khoá ghi SKIP LOCKED
     * như lockDue.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from AiJob j where j.status in ('QUEUED', 'WAITING_RETRY') and j.lane = :lane "
            + "and (j.attempts is null or j.attempts = 0) "
            + "and (j.nextAttemptAt is null or j.nextAttemptAt <= :now) "
            + "order by j.createdAt asc, j.id asc")
    List<AiJob> lockDueLane(@Param("now") Instant now, @Param("lane") String lane, Pageable page);

    /** Việc gửi LẺ: không có ngăn (việc cũ) hoặc đã lỗi ít nhất một lần. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select j from AiJob j where j.status in ('QUEUED', 'WAITING_RETRY') "
            + "and (j.lane is null or j.attempts > 0) "
            + "and (j.nextAttemptAt is null or j.nextAttemptAt <= :now) "
            + "order by j.createdAt asc, j.id asc")
    List<AiJob> lockDueSingles(@Param("now") Instant now, Pageable page);

    /** Việc còn chờ (QUEUED / WAITING_RETRY) tạo trước mốc — quá hạn chờ. */
    @Query("select j from AiJob j where j.status in ('QUEUED', 'WAITING_RETRY') and j.createdAt < :before")
    List<AiJob> findExpired(@Param("before") Instant before);

    /** Admin theo dõi lô (25/11): mọi việc của một lô. */
    List<AiJob> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    /** Việc kẹt RUNNING quá lâu (BE khởi động lại giữa chừng) → trả về hàng chờ. */
    @Modifying
    @Query("update AiJob j set j.status = 'QUEUED', j.lockedAt = null where j.status = 'RUNNING' and j.lockedAt < :before")
    int requeueStale(@Param("before") Instant before);

    /** Trang admin "Nhật ký việc AI": lọc tuỳ chọn theo status / kind, mới nhất trước. */
    @Query("select j from AiJob j where (:status is null or j.status = :status) and (:kind is null or j.kind = :kind) "
            + "and (:batchId is null or j.batchId = :batchId) order by j.createdAt desc, j.id desc")
    org.springframework.data.domain.Page<AiJob> search(@Param("status") String status, @Param("kind") String kind,
                                                       @Param("batchId") UUID batchId, Pageable page);

    /** Dùng lại theo hash gói (Hưng 19/11): việc cùng loại, cùng hash, đang ở các trạng thái cho trước — mới nhất trước. */
    List<AiJob> findByKindAndInputHashAndStatusInOrderByCreatedAtDesc(String kind, String inputHash,
                                                                     Collection<String> statuses);
}
