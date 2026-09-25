package com.doan.game.repository;

import com.doan.game.entity.Credential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CredentialRepository extends JpaRepository<Credential, UUID> {
}
