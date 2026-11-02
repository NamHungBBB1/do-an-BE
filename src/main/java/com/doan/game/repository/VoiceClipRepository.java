package com.doan.game.repository;

import com.doan.game.entity.VoiceClip;
import com.doan.game.enums.VoiceClipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VoiceClipRepository extends JpaRepository<VoiceClip, UUID> {

    Optional<VoiceClip> findByClipKey(String clipKey);

    List<VoiceClip> findByClipKeyIn(Collection<String> keys);

    List<VoiceClip> findTop50ByStatusOrderByRequestedAtAsc(VoiceClipStatus status);
}
