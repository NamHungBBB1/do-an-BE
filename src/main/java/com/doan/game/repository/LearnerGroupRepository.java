package com.doan.game.repository;

import com.doan.game.entity.LearnerGroup;
import com.doan.game.enums.LearningContext;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
public interface LearnerGroupRepository extends JpaRepository<LearnerGroup, UUID> {

    /** Đã từng mở nhóm (gia đình / lớp) — tài khoản khách hàng, không cấp vai nội bộ (23/10). */
    boolean existsByOwner_Id(UUID ownerId);

    /**
     * Khoá dòng nhóm (SELECT ... FOR UPDATE) khi mở slot: đếm slot rồi chèn mà không khoá thì
     * hai request song song cùng đếm 3/4 rồi cùng chèn, nhóm thành 5/4. Mẫu có sẵn:
     * TransactionRepository.lockByOrderCode. Kidz góp ý review 07/10.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from LearnerGroup g where g.id = :id")
    Optional<LearnerGroup> lockById(@Param("id") UUID id);

    /** Nhóm của người gọi, mới mở đến đóng — GET /api/groups sắp xếp lại trong service. */
    java.util.List<LearnerGroup> findByOwner_IdOrderByOpenedAtDesc(UUID ownerId);

    /**
     * Còn nhóm FAMILY đang mở không (17/11/2026: chỉ FAMILY còn độc quyền; CLASS mở bao nhiêu lớp cũng
     * được — kiểm bằng câu này thay vì để UNIQUE ném 500).
     */
    boolean existsByOwner_IdAndContextAndClosedAtIsNull(UUID ownerId,
                                                       com.doan.game.enums.LearningContext context);

    /**
     * Chỗ đã GIỮ của các lớp CLASS đang mở (18/11/2026). Một câu cho cả trang: sĩ số + số slot đã tạo
     * trong lớp (mọi trạng thái — slot đã xoá vẫn ăn chỗ của lớp), để tính `reserved` cho ví.
     */
    @Query("select g.id as groupId, g.slotLimit as plannedSize, "
            + "(select count(s) from LearnerSlot s where s.group.id = g.id) as created "
            + "from LearnerGroup g "
            + "where g.owner.id = :ownerId and g.context = :context and g.closedAt is null")
    List<GroupCapacityRow> openCapacity(@Param("ownerId") UUID ownerId,
                                        @Param("context") LearningContext context);

    /** Dòng kết quả của openCapacity — interface projection, không cần class mới. */
    interface GroupCapacityRow {
        UUID getGroupId();

        Integer getPlannedSize();

        long getCreated();
    }
}
