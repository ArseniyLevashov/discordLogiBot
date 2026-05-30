package com.example.bot2.repository;

import com.example.bot2.entity.EquipmentKill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EquipmentKillRepository extends JpaRepository<EquipmentKill, Long> {

    List<EquipmentKill> findByPanelIdOrderByCreatedAtDesc(Long panelId);

    List<EquipmentKill> findTop5ByPanelIdOrderByCreatedAtDesc(Long panelId);

    @Query("SELECT k.equipmentType.id, SUM(k.amount) FROM EquipmentKill k " +
            "WHERE k.panel.id = :panelId GROUP BY k.equipmentType.id")
    List<Object[]> sumByTypeForPanel(@Param("panelId") Long panelId);

    void deleteByPanelId(Long panelId);
}
