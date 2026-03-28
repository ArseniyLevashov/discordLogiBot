package com.example.bot2.repository;

import com.example.bot2.entity.DeliveryTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliveryTicketRepository extends JpaRepository<DeliveryTicket, Long> {
    List<DeliveryTicket> findByStatusOrderByCreatedAtDesc(DeliveryTicket.TicketStatus status);
    Optional<DeliveryTicket> findByDiscordMessageId(String messageId);
}
