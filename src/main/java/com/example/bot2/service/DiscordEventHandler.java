package com.example.bot2.service;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
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
            if (id.startsWith("status:")) {
                Long ticketId = Long.parseLong(id.split(":")[1]);
                return userHandler.handleStatusButton(event, ticketId);
            }
            return Mono.empty();
        }).subscribe();

        // Modal (форма с количеством)
        client.on(ModalSubmitInteractionEvent.class, event -> {
            String id = event.getCustomId();
            if (id.startsWith("deliver_modal:")) {
                Long ticketId = Long.parseLong(id.split(":")[1]);
                return userHandler.handleDeliverModal(event, ticketId);
            }
            return Mono.empty();
        }).subscribe();
    }
}
