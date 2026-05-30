package com.example.bot2.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "equipment_kills")
@Data
@NoArgsConstructor
public class EquipmentKill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "panel_id")
    private KillPanel panel;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "equipment_type_id")
    private EquipmentType equipmentType;

    private Integer amount;

    private String userId;

    private String username;

    private LocalDateTime createdAt = LocalDateTime.now();
}
