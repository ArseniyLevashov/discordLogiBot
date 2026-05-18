package com.example.bot2.repository;

import com.example.bot2.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
    Optional<Warehouse> findByNameIgnoreCase(String name);
    List<Warehouse> findAllByOrderByLastUpdatedAtDesc();

    List<Warehouse> findByLastUpdatedAtBefore(LocalDateTime threshold);
}
