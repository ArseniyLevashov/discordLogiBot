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
import reactor.util.retry.Retry;

@Component
@RequiredArgsConstructor
@Slf4j
public class DiscordEventHandler {

    private final GatewayDiscordClient client;
    private final AdminCommandHandler adminHandler;
    private final UserCommandHandler userHandler;
    private final WarehouseCommandHandler warehouseHandler;
    private final VacationCommandHandler vacationHandler;

    @PostConstruct
    public void registerListeners() {

        // Slash-команды
        client.on(ChatInputInteractionEvent.class, event ->
                safe(switch (event.getCommandName()) {
                    case "create-ticket"    -> adminHandler.handleCreateTicket(event);
                    case "cancel-ticket"    -> adminHandler.handleCancelTicket(event);
                    case "tickets"          -> adminHandler.handleListTickets(event);
                    case "cleanup-data"     -> adminHandler.handleCleanupCommand(event);
                    case "create-warehouse" -> warehouseHandler.handleCreateWarehouse(event);
                    case "warehouses"       -> warehouseHandler.handleListWarehouses(event);
                    case "update-warehouse" -> warehouseHandler.handleUpdateWarehouse(event);
                    case "delete-warehouse" -> warehouseHandler.handleDeleteWarehouse(event);
                    case "vacation-panel"   -> vacationHandler.handleVacationPanel(event);
                    case "vacations"        -> vacationHandler.handleListVacations(event);
                    case "end-vacation"     -> vacationHandler.handleEndVacation(event);
                    case "warehouse-panel"  -> warehouseHandler.handleCreatePanel(event);
                    default                 -> Mono.empty();
                })
        ).subscribe();

        // Кнопки
        client.on(ButtonInteractionEvent.class, event -> {
            String id = event.getCustomId();
            Mono<Void> action = Mono.empty();

            if (id.startsWith("deliver:")) {
                Long ticketId = Long.parseLong(id.split(":")[1]);
                action = userHandler.handleDeliverButton(event, ticketId);
            } else if (id.equals("cleanup_confirm")) {
                action = adminHandler.handleCleanupConfirm(event);
            } else if (id.equals("cleanup_cancel")) {
                action = adminHandler.handleCleanupCancel(event);
            } else if (id.equals("vacation_request")) {
                action = vacationHandler.handleVacationButton(event);
            }

            return safe(action);
        }).subscribe();

        // Select Menu
        client.on(SelectMenuInteractionEvent.class, event -> {
            String id = event.getCustomId();
            Mono<Void> action = Mono.empty();

            if (id.startsWith("resource_select:")) {
                Long ticketId = Long.parseLong(id.split(":")[1]);
                action = userHandler.handleResourceSelect(event, ticketId);
            } else if (id.equals("warehouse_update_select")) {
                action = warehouseHandler.handleUpdateWarehouseSelect(event);
            }

            return safe(action);
        }).subscribe();

        // Modals
        client.on(ModalSubmitInteractionEvent.class, event -> {
            String id = event.getCustomId();
            Mono<Void> action = Mono.empty();

            if (id.startsWith("create_ticket_modal:")) {
                String location = id.substring("create_ticket_modal:".length());
                action = adminHandler.handleCreateTicketModal(event, location);
            } else if (id.startsWith("deliver_modal:")) {
                String[] parts  = id.split(":");
                Long ticketId   = Long.parseLong(parts[1]);
                Long resourceId = Long.parseLong(parts[2]);
                action = userHandler.handleDeliverModal(event, ticketId, resourceId);
            } else if (id.equals("create_warehouse_modal")) {
                action = warehouseHandler.handleCreateWarehouseModal(event);
            } else if (id.equals("vacation_modal")) {
                action = vacationHandler.handleVacationModal(event);
            }

            return safe(action);
        }).subscribe();
    }

    /**
     * Оборачивает обработчик: 1 мгновенный повтор при сетевой ошибке,
     * затем перехват чтобы не ронять подписку и не сыпать стектрейс.
     */
    private Mono<Void> safe(Mono<Void> action) {
        return action
                .retryWhen(Retry.max(1).filter(this::isNetworkError))
                .onErrorResume(e -> {
                    if (isNetworkError(e)) {
                        log.warn("Сетевая ошибка при обработке interaction (повтор не помог): {}",
                                e.getMessage());
                    } else {
                        log.error("Ошибка обработки interaction: ", e);
                    }
                    return Mono.empty();
                });
    }

    /**
     * Проверяет всю цепочку причин на сетевые таймауты/разрывы.
     */
    private boolean isNetworkError(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String className = t.getClass().getName();
            String msg = t.getMessage();
            if (className.contains("NativeIoException")) return true;
            if (msg != null && (
                    msg.contains("Connection timed out")
                            || msg.contains("Connection reset")
                            || msg.contains("recvAddress")
                            || msg.contains("Connection refused"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}