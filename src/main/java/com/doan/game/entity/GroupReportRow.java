package com.doan.game.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một người học, một dòng. Chỉ mang con số đã tính cộng một tham chiếu tới chỗ ngồi — KHÔNG chép
 * tên vào. Số thì đông cứng, danh tính thì đọc sống, nên xoá một em là mọi báo cáo cũ tự hiện tên
 * mặc định.
 */
@Entity
@Table(name = "group_report_row")
@Getter
@Setter
@NoArgsConstructor
public class GroupReportRow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** GroupReport. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private GroupReport report;

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ChildSlot slot;

    @Column(name = "figures")
    private String figures;
}
