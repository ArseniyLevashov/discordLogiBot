package com.example.bot2.repository;

import com.example.bot2.entity.KillPanel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KillPanelRepository extends JpaRepository<KillPanel, Long> {

    Optional<KillPanel> findFirstByIsActiveTrueOrderByCreatedAtDesc();

    List<KillPanel> findByIsActiveTrue();
}
