package com.example.bot2.repository;

import com.example.bot2.entity.EquipmentCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EquipmentCategoryRepository extends JpaRepository<EquipmentCategory, Long> {
    List<EquipmentCategory> findAllByOrderByDisplayOrderAsc();
}
