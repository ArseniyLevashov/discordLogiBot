package com.example.bot2.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "vacations")
@Data
@NoArgsConstructor
public class Vacation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;
    private String username;
    private String guildId;
    private String channelId;

    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private VacationStatus status = VacationStatus.ACTIVE;

    private LocalDateTime createdAt = LocalDateTime.now();

    public enum VacationStatus { ACTIVE, COMPLETED }
}
