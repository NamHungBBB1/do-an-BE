package com.doan.game.auth.domain;

/**
 * Vai của một người lớn. KHÔNG lưu thành cột — suy ra từ gói đã mua trên Account.
 *
 * Chốt: "chọn 2 gói này mới được coi là phụ huynh/giáo viên". Chưa mua gói nào thì là GUEST.
 * Một người có thể giữ CẢ HAI vai (vừa là phụ huynh ở nhà, vừa là giáo viên ở trường),
 * nên đây là tập hợp, không phải một cột enum.
 */
public enum Role { GUEST, PARENT, TEACHER }
