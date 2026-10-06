package com.doan.game.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chỉ trùng khoá (23505) mới là "email đã có tài khoản"; NOT NULL (23502) là lỗi hệ thống. */
class UniqueViolationTest {

    @Test
    void duplicateKeyIsUniqueViolation() {
        assertTrue(AuthServiceImpl.isUniqueViolation(
                new DataIntegrityViolationException("x", new RuntimeException(new SQLException("dup", "23505")))));
    }

    @Test
    void notNullIsNotUniqueViolation() {
        assertFalse(AuthServiceImpl.isUniqueViolation(
                new DataIntegrityViolationException("x", new SQLException("NULL not allowed for column \"PARENT_PLAN\"", "23502"))));
        assertFalse(AuthServiceImpl.isUniqueViolation(new DataIntegrityViolationException("no cause")));
    }
}
