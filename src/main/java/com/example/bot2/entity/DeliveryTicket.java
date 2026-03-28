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

    private String location;
    private String createdBy;
    private String createdByName;
    private String discordMessageId;
    private String discordChannelId;

    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.OPEN;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime closedAt;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<TicketResource> resources = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<DeliveryContribution> contributions = new ArrayList<>();

    public enum TicketStatus { OPEN, COMPLETED, CANCELLED }

    // Тикет завершён когда ВСЕ ресурсы доставлены
    public boolean isAllCompleted() {
        return !resources.isEmpty() &&
                resources.stream().allMatch(TicketResource::isCompleted);
    }
}
