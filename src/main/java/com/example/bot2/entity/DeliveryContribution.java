package com.example.bot2.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_contributions")
@Data
@NoArgsConstructor
public class DeliveryContribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private DeliveryTicket ticket;

    // Ссылка на конкретный ресурс внутри тикета
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id")
    private TicketResource resource;

    private String userId;
    private String username;
    private long amount;
    private LocalDateTime contributedAt = LocalDateTime.now();
}
