package com.example.bot2.repository;

import com.example.bot2.entity.DeliveryContribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DeliveryContributionRepository extends JpaRepository<DeliveryContribution, Long> {
    List<DeliveryContribution> findByTicketIdOrderByContributedAtDesc(Long ticketId);

    // Топ контрибьюторов по тикету
    @Query("SELECT c.username, SUM(c.amount) as total " +
            "FROM DeliveryContribution c " +
            "WHERE c.ticket.id = :ticketId " +
            "GROUP BY c.userId, c.username " +
            "ORDER BY total DESC")
    List<Object[]> findTopContributorsByTicketId(@Param("ticketId") Long ticketId);

}

