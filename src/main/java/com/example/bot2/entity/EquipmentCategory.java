package com.example.bot2.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name="equipment_categories")
@Data
@NoArgsConstructor
public class EquipmentCategory {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private Long id;
    private String name;
    private Integer displayOrder;
}
