package com.example.bot2.service;

import org.springframework.stereotype.Service;
import com.example.bot2.entity.DeliveryContribution;
import com.example.bot2.entity.DeliveryTicket;
import com.example.bot2.repository.DeliveryContributionRepository;
import com.example.bot2.repository.DeliveryTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final DeliveryTicketRepository ticketRepo;
    private final DeliveryContributionRepository contributionRepo;

    private final EmbedBuilder embedBuilder;

    /**
     * Создать тикет (только менеджер)
     */
    public DeliveryTicket createTicket(String resourceName, String resourceEmoji,
                                       long targetAmount, String location,
                                       String creatorId, String creatorName) {
        DeliveryTicket ticket = new DeliveryTicket();
        ticket.setResourceName(resourceName);
        ticket.setResourceEmoji(resourceEmoji);
        ticket.setTargetAmount(targetAmount);
        ticket.setLocation(location);
        ticket.setCreatedBy(creatorId);
        ticket.setCreatedByName(creatorName);
        return ticketRepo.save(ticket);

    }

    /**
     * Привязать Discord-сообщение к тикету (после публикации embed)
     */
    public void attachMessage(Long ticketId, String messageId, String channelId) {
        ticketRepo.findById(ticketId).ifPresent(ticket -> {
            ticket.setDiscordMessageId(messageId);
            ticket.setDiscordChannelId(channelId);
            DeliveryTicket saved = ticketRepo.save(ticket);
            log.info("Saved ticket #{} with messageId={}", saved.getId(), saved.getDiscordMessageId());
        });
    }

    /**
     * Пользователь докладывает о доставке
     */
    public ContributeResult contribute(Long ticketId, String userId,
                                       String username, long amount) {
        DeliveryTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Тикет не найден: " + ticketId));

        if (ticket.getStatus() != DeliveryTicket.TicketStatus.OPEN) {
            return new ContributeResult(false, ticket, "А все! Тикет уже закрыт!");
        }
        if (amount <= 0) {
            return new ContributeResult(false, ticket, "Ты со мной шутки не шути");
        }

        // Записываем вклад
        DeliveryContribution contribution = new DeliveryContribution();
        contribution.setTicket(ticket);
        contribution.setUserId(userId);
        contribution.setUsername(username);
        contribution.setAmount(amount);
        contributionRepo.save(contribution);

        // Обновляем счётчик
        ticket.setDeliveredAmount(ticket.getDeliveredAmount() + amount);

        // Проверяем завершение
        boolean justCompleted = false;
        if (ticket.isCompleted() && ticket.getStatus() == DeliveryTicket.TicketStatus.OPEN) {
            ticket.setStatus(DeliveryTicket.TicketStatus.COMPLETED);
            ticket.setClosedAt(LocalDateTime.now());
            justCompleted = true;
        }

        ticketRepo.save(ticket);
        log.info("Contribution: user={} ticket={} amount={}", username, ticketId, amount);

        return new ContributeResult(true, ticket, justCompleted ? "COMPLETED" : "OK");
    }

    /**
     * Отменить тикет (только менеджер)
     */
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

    public Optional<DeliveryTicket> getTicketByMessageId(String messageId) {
        return ticketRepo.findByDiscordMessageId(messageId);
    }

    public Optional<DeliveryTicket> getTicketById(Long id) {
        return ticketRepo.findById(id);
    }

    public List<Object[]> getTopContributors(Long ticketId) {
        return contributionRepo.findTopContributorsByTicketId(ticketId);
    }

    @lombok.Value
    public static class ContributeResult {
        boolean success;
        DeliveryTicket ticket;
        String message;
    }
}
