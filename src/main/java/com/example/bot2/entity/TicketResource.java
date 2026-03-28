package com.example.bot2.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ticket_resources")
@Data
@NoArgsConstructor
public class TicketResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private DeliveryTicket ticket;

    private String resourceName;   // "Железо"
    private String resourceEmoji;  // "⚙️"
    private long targetAmount;     // 10000
    private long deliveredAmount;  // 0

    public int getProgressPercent() {
        if (targetAmount == 0) return 100;
        return (int) Math.min(100, (deliveredAmount * 100L) / targetAmount);
    }

    public boolean isCompleted() {
        return deliveredAmount >= targetAmount;
    }
}
