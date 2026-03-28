package com.example.bot2.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table(name = "delivery_contributions")
@Data
@Entity
@NoArgsConstructor
public class DeliveryContribution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "ticket_id")
    private DeliveryTicket ticket;

    private String userId;        // Discord ID пользователя
    private String username;      // Имя пользователя
    private long amount;          // Сколько привёз

    private LocalDateTime contributedAt = LocalDateTime.now();
}
