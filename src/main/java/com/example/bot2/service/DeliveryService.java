package com.example.bot2.service;

import com.example.bot2.entity.TicketResource;
import com.example.bot2.repository.TicketResourceRepository;
import org.springframework.stereotype.Service;
import com.example.bot2.entity.DeliveryContribution;
import com.example.bot2.entity.DeliveryTicket;
import com.example.bot2.repository.DeliveryContributionRepository;
import com.example.bot2.repository.DeliveryTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final DeliveryTicketRepository ticketRepo;
    private final DeliveryContributionRepository contributionRepo;
    private final TicketResourceRepository resourceRepo;

    /**
     * Создать тикет с несколькими ресурсами
     * resources — список пар: "⚙️Железо:10000", "🌲Дерево:5000"
     */
    public DeliveryTicket createTicket(List<String> resourceSpecs, String location,
                                       String description,
                                       String creatorId, String creatorName) {
        DeliveryTicket ticket = new DeliveryTicket();
        ticket.setLocation(location);
        ticket.setDescription(description);
        ticket.setCreatedBy(creatorId);
        ticket.setCreatedByName(creatorName);
        DeliveryTicket saved = ticketRepo.save(ticket);

        log.info("Creating ticket #{} with {} specs", saved.getId(), resourceSpecs.size());

        for (String spec : resourceSpecs) {
            String[] parts = spec.split(":", 2);
            if (parts.length < 2) continue;

            String name = parts[0].trim();
            long amount;
            try {
                amount = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid spec '{}'", spec);
                continue;
            }

            TicketResource resource = new TicketResource();
            resource.setTicket(saved);
            resource.setResourceName(name);
            resource.setTargetAmount(amount);
            resource.setDeliveredAmount(0L);
            resourceRepo.save(resource);
            log.info("Saved resource: {} x{}", name, amount);
        }

        // Сбрасываем кэш Hibernate и загружаем свежие данные
        ticketRepo.flush();
        return ticketRepo.findById(saved.getId())
                .orElseThrow(() -> new RuntimeException("Ticket not found after save"));
    }

    /**
     * Пользователь доставляет ресурс — указывает номер ресурса и количество
     */
    public ContributeResult contribute(Long ticketId, Long resourceId,
                                       String userId, String username, long amount) {
        DeliveryTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Тикет не найден"));

        if (ticket.getStatus() != DeliveryTicket.TicketStatus.OPEN) {
            return new ContributeResult(false, ticket, "Тикет уже закрыт!");
        }

        TicketResource resource = resourceRepo.findById(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Ресурс не найден"));

        if (!resource.getTicket().getId().equals(ticketId)) {
            return new ContributeResult(false, ticket, "Ресурс не принадлежит этому тикету!");
        }

        // Записываем вклад
        DeliveryContribution contribution = new DeliveryContribution();
        contribution.setTicket(ticket);
        contribution.setResource(resource);
        contribution.setUserId(userId);
        contribution.setUsername(username);
        contribution.setAmount(amount);
        contributionRepo.save(contribution);

        // Обновляем количество у конкретного ресурса
        resource.setDeliveredAmount(resource.getDeliveredAmount() + amount);
        resourceRepo.save(resource);

        // Проверяем завершение всего тикета
        boolean justCompleted = false;
        DeliveryTicket freshTicket = ticketRepo.findById(ticketId).orElse(ticket);
        if (freshTicket.isAllCompleted()) {
            freshTicket.setStatus(DeliveryTicket.TicketStatus.COMPLETED);
            freshTicket.setClosedAt(LocalDateTime.now());
            ticketRepo.save(freshTicket);
            justCompleted = true;
        }

        log.info("Contribution: user={} ticket={} resource={} amount={}",
                username, ticketId, resource.getResourceName(), amount);
        return new ContributeResult(true, freshTicket, justCompleted ? "COMPLETED" : "OK");
    }

    public void attachMessage(Long ticketId, String messageId, String channelId) {
        ticketRepo.findById(ticketId).ifPresent(ticket -> {
            ticket.setDiscordMessageId(messageId);
            ticket.setDiscordChannelId(channelId);
            ticketRepo.save(ticket);
        });
    }

    public Optional<DeliveryTicket> cancelTicket(Long ticketId) {
        return ticketRepo.findById(ticketId).map(ticket -> {
            ticket.setStatus(DeliveryTicket.TicketStatus.CANCELLED);
            ticket.setClosedAt(LocalDateTime.now());
            return ticketRepo.save(ticket);
        });
    }

    public List<DeliveryTicket> getOpenTickets() {
        return ticketRepo.findByStatusOrderByCreatedAtDesc(DeliveryTicket.TicketStatus.OPEN);
    }

    public Optional<DeliveryTicket> getTicketById(Long id) {
        return ticketRepo.findById(id);
    }

    @lombok.Value
    public static class ContributeResult {
        boolean success;
        DeliveryTicket ticket;
        String message;
    }

    public List<Object[]> getTopContributors(Long ticketId) {
        return contributionRepo.findTopContributorsByTicketId(ticketId);
    }
}
