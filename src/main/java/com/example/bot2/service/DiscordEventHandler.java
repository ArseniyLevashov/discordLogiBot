package com.example.bot2.service;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class DiscordEventHandler {

    private final GatewayDiscordClient client;
    private final AdminCommandHandler adminHandler;
    private final UserCommandHandler userHandler;

    @PostConstruct
    public void registerListeners() {

        // Slash-команды
        client.on(ChatInputInteractionEvent.class, event ->
                switch (event.getCommandName()) {
                    case "create-ticket" -> adminHandler.handleCreateTicket(event);
                    case "cancel-ticket" -> adminHandler.handleCancelTicket(event);
                    case "tickets"       -> adminHandler.handleListTickets(event);
                    default              -> Mono.empty();
                }
        ).subscribe();

        // Кнопки
        client.on(ButtonInteractionEvent.class, event -> {
            String id = event.getCustomId();
            if (id.startsWith("deliver:")) {
                Long ticketId = Long.parseLong(id.split(":")[1]);
                return userHandler.handleDeliverButton(event, ticketId);
            }
            return Mono.empty();
        }).subscribe();

        // Select Menu — выбор ресурса
        client.on(SelectMenuInteractionEvent.class, event -> {
            String id = event.getCustomId();
            if (id.startsWith("resource_select:")) {
                Long ticketId = Long.parseLong(id.split(":")[1]);
                return userHandler.handleResourceSelect(event, ticketId);
            }
            return Mono.empty();
        }).subscribe();

        // Modals
        client.on(ModalSubmitInteractionEvent.class, event -> {
            String id = event.getCustomId();

            // Modal создания тикета
            if (id.startsWith("create_ticket_modal:")) {
                String location = id.substring("create_ticket_modal:".length());
                return adminHandler.handleCreateTicketModal(event, location);
            }

            // Modal доставки ресурса
            if (id.startsWith("deliver_modal:")) {
                String[] parts  = id.split(":");
                Long ticketId   = Long.parseLong(parts[1]);
                Long resourceId = Long.parseLong(parts[2]);
                return userHandler.handleDeliverModal(event, ticketId, resourceId);
            }

            return Mono.empty();
        }).subscribe();
    }
}
