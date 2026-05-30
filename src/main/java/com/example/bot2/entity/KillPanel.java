package com.example.bot2.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "kill_panels")
@Data
@NoArgsConstructor
public class KillPanel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String messageId;

    private String channelId;

    private Boolean isActive = true;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime closedAt;
}


