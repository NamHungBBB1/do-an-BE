package com.doan.game.repository;

import com.doan.game.entity.AiCallLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {

    /**
     * Có ruột (19/11 đợt D): trang admin "Nhật ký gọi AI" — lọc tuỳ chọn, mới nhất trước. from / to luôn có giá trị (service
     * thay null bằng mốc rất xa) để Postgres không phải đoán kiểu tham số null.
     */
    @Query("select l from AiCallLog l where (:alias is null or l.keyAlias = :alias) "
            + "and (:outcome is null or l.outcome = :outcome) and (:kind is null or l.kind = :kind) "
            + "and l.createdAt >= :from and l.createdAt < :to order by l.createdAt desc, l.id desc")
    Page<AiCallLog> search(@Param("alias") String alias, @Param("outcome") String outcome, @Param("kind") String kind,
                           @Param("from") Instant from, @Param("to") Instant to, Pageable page);

    /** Sức khoẻ khoá: [keyAlias, outcome, số lần, tổng token vào, tổng token ra] từ mốc since. */
    @Query("select l.keyAlias, l.outcome, count(l), coalesce(sum(l.tokensIn), 0), coalesce(sum(l.tokensOut), 0) "
            + "from AiCallLog l where l.createdAt >= :since group by l.keyAlias, l.outcome")
    List<Object[]> countsSince(@Param("since") Instant since);

    /** [keyAlias, lần OK gần nhất] trong phần nhật ký còn giữ. */
    @Query("select l.keyAlias, max(l.createdAt) from AiCallLog l where l.outcome = 'OK' group by l.keyAlias")
    List<Object[]> lastOk();

    /** Giới hạn RPM / RPD: số lần gọi của các khoá cho trước từ mốc since. */
    long countByKeyAliasInAndCreatedAtGreaterThanEqual(Collection<String> aliases, Instant since);

    List<AiCallLog> findByAiJobIdOrderByCreatedAtAsc(UUID aiJobId);

    /** Admin theo dõi lô (25/11): các lần gọi có mã lô, lọc tuỳ chọn, mới nhất trước. */
    @Query("select l from AiCallLog l where l.batchId is not null "
            + "and (:keyAlias is null or l.keyAlias = :keyAlias) and (:outcome is null or l.outcome = :outcome) "
            + "and l.createdAt >= :from and l.createdAt < :to order by l.createdAt desc, l.id desc")
    org.springframework.data.domain.Page<AiCallLog> searchBatches(@Param("keyAlias") String keyAlias,
                                                                 @Param("outcome") String outcome,
                                                                 @Param("from") Instant from, @Param("to") Instant to,
                                                                 org.springframework.data.domain.Pageable page);

    List<AiCallLog> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    /** Lô đã viết xong hôm nay theo khoá: [alias, số lô, số bài]. Lô = lần gọi OK có batchSize ≥ 2. */
    @Query("select l.keyAlias, count(l), coalesce(sum(l.batchSize), 0) from AiCallLog l where l.createdAt >= :since "
            + "and l.batchSize >= 2 and l.outcome = 'OK' group by l.keyAlias")
    List<Object[]> batchesSince(@Param("since") Instant since);

    /** Giữ 30 ngày (khung 13.4). */
    @Modifying
    @Query("delete from AiCallLog l where l.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
