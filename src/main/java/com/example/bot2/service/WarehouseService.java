package com.example.bot2.service;

import com.example.bot2.entity.Warehouse;
import com.example.bot2.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class WarehouseService {

    private final WarehouseRepository warehouseRepo;

    public Warehouse createWarehouse(String name, String location, String description,
                                     String password, String creatorName) {
        if (warehouseRepo.findByNameIgnoreCase(name).isPresent()) {
            throw new IllegalArgumentException("Склад '" + name + "' уже существует");
        }

        Warehouse w = new Warehouse();
        w.setName(name);
        w.setLocation(location);
        w.setDescription(description);
        w.setPassword(password);
        w.setCreatedBy(creatorName);
        w.setLastUpdatedBy(creatorName);
        return warehouseRepo.save(w);
    }

    public List<Warehouse> getAllWarehouses() {
        return warehouseRepo.findAllByOrderByLastUpdatedAtDesc();
    }

    /**
     * Обновить дату последнего обновления для одного или нескольких складов.
     */
    public UpdateResult updateLastUpdated(List<String> names, String userName) {
        List<String> updated = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        for (String name : names) {
            Optional<Warehouse> opt = warehouseRepo.findByNameIgnoreCase(name.trim());
            if (opt.isPresent()) {
                Warehouse w = opt.get();
                w.setLastUpdatedAt(LocalDateTime.now());
                w.setLastUpdatedBy(userName);
                warehouseRepo.save(w);
                updated.add(w.getName());
            } else {
                notFound.add(name.trim());
            }
        }
        log.info("User {} updated: {}, not found: {}", userName, updated, notFound);
        return new UpdateResult(updated, notFound);
    }

    public boolean deleteWarehouse(String name) {
        Optional<Warehouse> opt = warehouseRepo.findByNameIgnoreCase(name);
        if (opt.isPresent()) {
            warehouseRepo.delete(opt.get());
            return true;
        }
        return false;
    }

    @lombok.Value
    public static class UpdateResult {
        List<String> updated;
        List<String> notFound;
    }
}
