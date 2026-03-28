package com.example.bot2.repository;

import com.example.bot2.entity.TicketResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketResourceRepository extends JpaRepository<TicketResource, Long> {
    List<TicketResource> findByTicketId(Long ticketId);
}
