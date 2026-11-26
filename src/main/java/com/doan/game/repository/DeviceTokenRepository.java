package com.doan.game.repository;

import com.doan.game.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Token push (26/11). Có ruột → nằm trong GIU_LAI của ops/sinh-khung-be.py. */
@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    /** Đăng ký lại cùng máy: tìm dòng cũ theo token để đổi chủ / cập nhật lastSeenAt (token UNIQUE). */
    Optional<DeviceToken> findByToken(String token);

    /** Mọi máy của một tài khoản — gửi thông báo đẩy. */
    List<DeviceToken> findByAccount_Id(UUID accountId);
}
