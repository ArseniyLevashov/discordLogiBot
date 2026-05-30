package com.example.bot2.service;

import com.example.bot2.entity.EquipmentCategory;
import com.example.bot2.entity.EquipmentKill;
import com.example.bot2.entity.EquipmentType;
import com.example.bot2.entity.KillPanel;
import com.example.bot2.repository.EquipmentCategoryRepository;
import com.example.bot2.repository.EquipmentKillRepository;
import com.example.bot2.repository.EquipmentTypeRepository;
import com.example.bot2.repository.KillPanelRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class KillService {

    private final EquipmentCategoryRepository categoryRepo;
    private final EquipmentTypeRepository typeRepo;
    private final KillPanelRepository panelRepo;
    private final EquipmentKillRepository killRepo;

    public List<EquipmentCategory> getCategories() {
        return categoryRepo.findAllByOrderByDisplayOrderAsc();
    }

    public List<EquipmentType> getTypesByCategory(Long categoryId) {
        return typeRepo.findByCategoryIdOrderByDisplayOrderAsc(categoryId);
    }

    public Optional<EquipmentType> getType(Long id) {
        return typeRepo.findById(id);
    }

    public Optional<KillPanel> getActivePanel() {
        return panelRepo.findFirstByIsActiveTrueOrderByCreatedAtDesc();
    }

    public Optional<KillPanel> getPanelById(Long id) { return panelRepo.findById(id); }

    /** Создать новую активную панель. Старую активную деактивирует. */
    public KillPanel createNewPanel() {
        // Деактивируем все предыдущие активные
        panelRepo.findByIsActiveTrue().forEach(p -> {
            p.setIsActive(false);
            panelRepo.save(p);
        });
        KillPanel panel = new KillPanel();
        return panelRepo.save(panel);
    }

    public void attachMessage(Long panelId, String messageId, String channelId) {
        panelRepo.findById(panelId).ifPresent(p -> {
            p.setMessageId(messageId);
            p.setChannelId(channelId);
            panelRepo.save(p);
        });
    }

    /** Закрыть подсчёт: удалить все записи активной панели и деактивировать её. */
    public Optional<KillPanel> closeActivePanel() {
        Optional<KillPanel> active = getActivePanel();
        active.ifPresent(panel -> {
            killRepo.deleteByPanelId(panel.getId());
            panel.setIsActive(false);
            panel.setClosedAt(LocalDateTime.now());
            panelRepo.save(panel);
            log.info("Panel #{} closed, kills cleared", panel.getId());
        });
        return active;
    }

    /** Записать уничтожение в активную панель. */
    public Optional<EquipmentKill> recordKill(Long typeId, int amount,
                                              String userId, String username) {
        Optional<KillPanel> active = getActivePanel();
        if (active.isEmpty()) return Optional.empty();

        EquipmentType type = typeRepo.findById(typeId).orElse(null);
        if (type == null) return Optional.empty();

        EquipmentKill kill = new EquipmentKill();
        kill.setPanel(active.get());
        kill.setEquipmentType(type);
        kill.setAmount(amount);
        kill.setUserId(userId);
        kill.setUsername(username);
        return Optional.of(killRepo.save(kill));
    }

    public Optional<EquipmentKill> getKill(Long id) { return killRepo.findById(id); }

    public Optional<EquipmentKill> editKill(Long killId, int newAmount) {
        return killRepo.findById(killId).map(k -> {
            k.setAmount(newAmount);
            return killRepo.save(k);
        });
    }

    public boolean deleteKill(Long killId) {
        if (killRepo.existsById(killId)) {
            killRepo.deleteById(killId);
            return true;
        }
        return false;
    }

    /** Суммы по типу для активной панели: Map<typeId, totalAmount> */
    public Map<Long, Long> getTotalsByType(Long panelId) {
        Map<Long, Long> totals = new HashMap<>();
        for (Object[] row : killRepo.sumByTypeForPanel(panelId)) {
            totals.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return totals;
    }

    public List<EquipmentKill> getLast5(Long panelId) {
        return killRepo.findTop5ByPanelIdOrderByCreatedAtDesc(panelId);
    }
}
