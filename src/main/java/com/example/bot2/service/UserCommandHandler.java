package com.example.bot2.service;

import com.example.bot2.entity.TicketResource;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.SelectMenu;
import discord4j.core.object.component.TextInput;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserCommandHandler {

    private final DeliveryService deliveryService;
    private final EmbedBuilder embedBuilder;
    private final AdminCommandHandler adminHandler;

    /**
     * Кнопка "Я доставил!" — показываем Select Menu с выбором ресурса
     */
    public Mono<Void> handleDeliverButton(ButtonInteractionEvent event, Long ticketId) {
        return deliveryService.getTicketById(ticketId)
                .map(ticket -> {
                    List<SelectMenu.Option> options = ticket.getResources().stream()
                            .filter(r -> !r.isCompleted())
                            .map(r -> SelectMenu.Option.of(
                                    r.getResourceName() +
                                            " (" + r.getDeliveredAmount() + "/" + r.getTargetAmount() + ")",
                                    r.getId().toString()
                            ))
                            .collect(Collectors.toList());

                    if (options.isEmpty()) {
                        return event.reply()
                                .withEphemeral(true)
                                .withContent("✅ Все ресурсы уже доставлены!");
                    }

                    SelectMenu selectMenu = SelectMenu.of(
                            "resource_select:" + ticketId,
                            options
                    ).withPlaceholder("Выбери ресурс который ты доставил");

                    return event.reply()
                            .withEphemeral(true)
                            .withContent("Какой ресурс ты доставил?")
                            .withComponents(ActionRow.of(selectMenu));
                })
                .orElse(event.reply().withEphemeral(true).withContent("❌ Тикет не найден"))
                .then();
    }

    /**
     * Пользователь выбрал ресурс — открываем Modal с полем количества
     */
    public Mono<Void> handleResourceSelect(SelectMenuInteractionEvent event,
                                           Long ticketId) {
        String resourceId = event.getValues().get(0);

        return event.presentModal()
                .withTitle("📦 Сколько доставил?")
                .withCustomId("deliver_modal:" + ticketId + ":" + resourceId)
                .withComponents(ActionRow.of(
                        TextInput.small("amount_input", "Количество", 1, 15)
                                .required(true)
                                .placeholder("Например: 500")
                ));
    }

    /**
     * Modal отправлен — записываем доставку
     */
    public Mono<Void> handleDeliverModal(ModalSubmitInteractionEvent event,
                                         Long ticketId, Long resourceId) {
        String userId   = event.getInteraction().getUser().getId().asString();
        String username = event.getInteraction().getUser().getUsername();

        String rawAmount = event.getComponents(TextInput.class).stream()
                .filter(c -> c.getCustomId().equals("amount_input"))
                .findFirst()
                .flatMap(TextInput::getValue)
                .orElse("0");

        long amount;
        try {
            amount = Long.parseLong(rawAmount.trim().replace(",", "").replace(" ", ""));
        } catch (NumberFormatException e) {
            return event.reply().withEphemeral(true).withContent("❌ Введи корректное число!");
        }

        return Mono.fromCallable(() ->
                deliveryService.contribute(ticketId, resourceId, userId, username, amount)
        ).flatMap(result -> {
            // Получаем название ресурса для подтверждения
            TicketResource resource = result.getTicket().getResources().stream()
                    .filter(r -> r.getId().equals(resourceId))
                    .findFirst()
                    .orElse(null);

            String resName  = resource != null ? resource.getResourceName()  : "";

            EmbedCreateSpec confirm = embedBuilder.buildContributionConfirmEmbed(
                    result, resName, amount);

            return event.reply()
                    .withEphemeral(true)
                    .withEmbeds(confirm)
                    .then(Mono.defer(() ->
                            adminHandler.refreshTicketMessage(result.getTicket(), event.getClient())
                    ));
        }).onErrorResume(e -> {
            log.error("Error in handleDeliverModal: ", e);
            return event.reply().withEphemeral(true).withContent("❌ Ошибка: " + e.getMessage());
        });
    }
}
