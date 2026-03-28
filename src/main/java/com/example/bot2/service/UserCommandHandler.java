package com.example.bot2.service;

import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.TextInput;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserCommandHandler {

    private final DeliveryService deliveryService;
    private final EmbedBuilder embedBuilder;
    private final AdminCommandHandler adminHandler;

    /**
     * Нажатие кнопки "Я доставил!"
     * Открывает Modal с полем для ввода количества
     */
    public Mono<Void> handleDeliverButton(ButtonInteractionEvent event, Long ticketId) {
        TextInput amountInput = TextInput.small(
                "amount_input",
                "Сколько привёз?",
                1, 10
        ).required(true).placeholder("Например: 5000");

        return event.presentModal()
                .withTitle("📦 Записать доставку")
                .withCustomId("deliver_modal:" + ticketId)
                .withComponents(ActionRow.of(amountInput));
    }

    /**
     * Нажатие кнопки "Мой вклад"
     */
    public Mono<Void> handleStatusButton(ButtonInteractionEvent event, Long ticketId) {
        String userId = event.getInteraction().getUser().getId().asString();
        String username = event.getInteraction().getUser().getUsername();

        return deliveryService.getTicketById(ticketId)
                .map(ticket -> {
                    List<Object[]> top = deliveryService.getTopContributors(ticketId);

                    // Найти вклад текущего пользователя
                    long myTotal = top.stream()
                            .filter(row -> userId.equals(((String) row[0])))
                            .mapToLong(row -> ((Number) row[2]).longValue())
                            .sum();

                    String desc = myTotal > 0
                            ? String.format("Ты молодец, ты доставил **%,d %s** по этому тикету",
                            myTotal, ticket.getResourceEmoji())
                            : "Ты ещё ничего не доставлял по этому тикету (исправляйся)";

                    EmbedCreateSpec embed = discord4j.core.spec.EmbedCreateSpec.builder()
                            .color(Color.of(0x9B59B6))
                            .title("📊 Твой слонярский вклад")
                            .description(desc)
                            .addField("Общий прогресс",
                                    ticket.getProgressPercent() + "%", true)
                            .build();

                    return event.reply().withEphemeral(true).withEmbeds(embed);
                })
                .orElse(event.reply().withEphemeral(true).withContent("❌ Тикет не найден"))
                .then();
    }

    /**
     * Отправка Modal с количеством доставки
     */
    public Mono<Void> handleDeliverModal(ModalSubmitInteractionEvent event, Long ticketId) {
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

        // Выполняем contribute в отдельном Mono чтобы транзакция точно закрылась
        return Mono.fromCallable(() ->
                        deliveryService.contribute(ticketId, userId, username, amount)
                )
                .flatMap(result -> {
                    EmbedCreateSpec confirmEmbed = embedBuilder.buildContributionConfirmEmbed(result, amount);

                    return event.reply()
                            .withEphemeral(true)
                            .withEmbeds(confirmEmbed)
                            .then(Mono.defer(() ->
                                    adminHandler.refreshTicketMessage(result.getTicket(), event.getClient())
                            ));
                });
                /*.onErrorResume(e -> {
                    log.error("Error in handleDeliverModal: ", e);
                    return event.reply().withEphemeral(true).withContent("❌ Ошибка: " + e.getMessage());
                });*/
    }
}
