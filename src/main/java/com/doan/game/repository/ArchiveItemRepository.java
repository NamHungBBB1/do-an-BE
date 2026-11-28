package com.doan.game.repository;

import com.doan.game.entity.ArchiveItem;
import com.doan.game.enums.ArchiveKind;
import com.doan.game.enums.ArchiveState;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Có ruột (27/11, vòng đời lưu trữ): bộ sinh khung giữ nguyên tệp này. Mọi khoá nhiều dòng đều xếp theo id để job, máy lưu trữ
 * và request Studio khoá cùng một thứ tự (không deadlock).
 */
@Repository
public interface ArchiveItemRepository extends JpaRepository<ArchiveItem, UUID> {

    Optional<ArchiveItem> findByKindAndItemRef(ArchiveKind kind, String itemRef);

    List<ArchiveItem> findByKindIn(Collection<ArchiveKind> kinds);

    List<ArchiveItem> findByStateIn(Collection<ArchiveState> states);

    List<ArchiveItem> findByKindInAndState(Collection<ArchiveKind> kinds, ArchiveState state);

    List<ArchiveItem> findByItemRefInAndKindIn(Collection<String> refs, Collection<ArchiveKind> kinds);

    long countByState(ArchiveState state);

    /** Số bản của một dòng RELEASE (rỗng = không có dòng hoặc là tệp) — đọc mà không nạp thực thể. */
    @Query("select i.itemRef from ArchiveItem i where i.id = :id and i.kind = com.doan.game.enums.ArchiveKind.RELEASE")
    Optional<String> findReleaseRef(@Param("id") UUID id);

    /** Job: khoá cả bảng (vài nghìn dòng, một lần mỗi đêm) để claim / Giữ lại / khôi phục chạy sau nó. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ArchiveItem i order by i.id")
    List<ArchiveItem> lockAll();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ArchiveItem i where i.id = :id")
    Optional<ArchiveItem> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ArchiveItem i where i.id in :ids order by i.id")
    List<ArchiveItem> lockByIds(@Param("ids") Collection<UUID> ids);

    /** Chốt chặn ở luồng nội dung: khoá dòng của các đường dẫn đang gắn — claim chạy cùng lúc phải chờ (hoặc thấy) lần ghi này. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ArchiveItem i where i.itemRef in :refs and i.kind in :kinds order by i.id")
    List<ArchiveItem> lockByRefs(@Param("refs") Collection<String> refs, @Param("kinds") Collection<ArchiveKind> kinds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ArchiveItem i where i.kind = :kind and i.itemRef = :ref")
    Optional<ArchiveItem> lockByKindAndRef(@Param("kind") ArchiveKind kind, @Param("ref") String ref);

    /** Việc tới hạn cho máy lưu trữ, cũ nhất trước (chỉ đọc id; khoá sau bằng lockByIds). */
    @Query("select i.id from ArchiveItem i where i.kind in :kinds and i.state = :state and i.dueAt <= :now order by i.dueAt, i.id")
    List<UUID> findDueIds(@Param("kinds") Collection<ArchiveKind> kinds, @Param("state") ArchiveState state,
                          @Param("now") Instant now, Pageable page);

    /** Lần cuối nhận lô chơi (bản 1) theo bản phát hành — một bản còn người chơi thì chưa đếm. */
    @Query("select c.buildVersion, max(c.receivedAt) from ChoiceEvent c where c.buildVersion is not null group by c.buildVersion")
    List<Object[]> lastChoiceEventByVersion();

    /** Như trên cho lô bản 2 (PickEvent). */
    @Query("select p.buildVersion, max(p.receivedAt) from PickEvent p where p.buildVersion is not null group by p.buildVersion")
    List<Object[]> lastPickEventByVersion();
}
