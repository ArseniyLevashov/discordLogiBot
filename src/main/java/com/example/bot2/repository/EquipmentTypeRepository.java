package com.example.bot2.repository;


import com.example.bot2.entity.EquipmentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EquipmentTypeRepository extends JpaRepository<EquipmentType, Long> {
    List<EquipmentType> findByCategoryIdOrderByDisplayOrderAsc(Long categoryId);
}
