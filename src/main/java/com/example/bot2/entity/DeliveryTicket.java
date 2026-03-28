package com.example.bot2.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "delivery_tickets")
@Data
@NoArgsConstructor
public class DeliveryTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String resourceName;      // "сальвага", "компачи", "сера"
    private String resourceEmoji;     // "⚙️", "🌲", "🍎"
    private long targetAmount;        // цель: 10000 единиц
    private long deliveredAmount;     // уже привезли

    private String location;          // куда везти
    private String createdBy;         // Discord ID создателя
    private String createdByName;     // имя создателя

    private String discordMessageId;  // ID сообщения в Discord (для обновления embed)
    private String discordChannelId;  // ID канала

    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.OPEN;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime closedAt;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<DeliveryContribution> contributions = new ArrayList<>();

    public enum TicketStatus { OPEN, COMPLETED, CANCELLED }

    // Прогресс в процентах (0-100)
    public int getProgressPercent() {
        if (targetAmount == 0) return 100;
        return (int) Math.min(100, (deliveredAmount * 100L) / targetAmount);
    }

    public boolean isCompleted() {
        return deliveredAmount >= targetAmount;
    }
}
