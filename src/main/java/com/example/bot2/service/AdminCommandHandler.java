package com.example.bot2.service;

import com.example.bot2.entity.DeliveryTicket;
import com.example.bot2.entity.TicketResource;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.LayoutComponent;
import discord4j.core.object.component.TextInput;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminCommandHandler {

    private final DeliveryService deliveryService;
    private final EmbedBuilder embedBuilder;

    @Value("${discord.manager-role-id}")
    private String managerRoleId;

    /**
     * /create-ticket resource:Железо emoji:⚙️ amount:10000 location:Склад-3
     */
    public Mono<Void> handleCreateTicket(ChatInputInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(managerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("❌ У тебя нет прав для создания тикетов!");
        }

        String location = getStringOption(event, "location");

        // Открываем Modal с полями для ресурсов
        return event.presentModal()
                .withTitle("📦 Ресурсы для доставки")
                .withCustomId("create_ticket_modal:" + location)
                .withComponents(
                        ActionRow.of(
                                TextInput.small("resource_1", "Ресурс 1 (название:количество)", 1, 50)
                                        .required(true)
                                        .placeholder("Сальвага:100000")
                        ),
                        ActionRow.of(
                                TextInput.small("resource_2", "Ресурс 2", 1, 50)
                                        .required(false)
                                        .placeholder("Компоненты:50000")
                        ),
                        ActionRow.of(
                                TextInput.small("resource_3", "Ресурс 3", 1, 50)
                                        .required(false)
                                        .placeholder("Уголь:30000")
                        ),
                        ActionRow.of(
                                TextInput.small("resource_4", "Ресурс 4", 1, 50)
                                        .required(false)
                                        .placeholder("Сера:30000")
                        ),
                        ActionRow.of(
                                TextInput.small("resource_5", "Ресурс 5", 1, 50)
                                        .required(false)
                                        .placeholder("Кокс:20000")
                        )
                );
    }

    public Mono<Void> handleCreateTicketModal(ModalSubmitInteractionEvent event, String location) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(managerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply().withEphemeral(true).withContent("❌ Духи завода не подчинятся самозванцу!");
        }

        // Собираем заполненные поля ресурсов
        List<String> resourceSpecs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final String fieldId = "resource_" + i;
            event.getComponents(TextInput.class).stream()
                    .filter(c -> c.getCustomId().equals(fieldId))
                    .findFirst()
                    .flatMap(TextInput::getValue)
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .ifPresent(resourceSpecs::add);
        }

        if (resourceSpecs.isEmpty()) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("❌ Ну ты хоть че-нибудь напиши, дурень");
        }

        // Валидация формата
        List<String> invalid = resourceSpecs.stream()
                .filter(spec -> spec.split(":").length < 2)
                .collect(Collectors.toList());

        if (!invalid.isEmpty()) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("❌ Неверный формат: `" + String.join(", ", invalid) +
                            "`\nФормат: `название:количество` (напр. `Железо:10000`)");
        }

        // Валидация чисел
        for (String spec : resourceSpecs) {
            try {
                long amount = Long.parseLong(spec.split(":", 2)[1].trim());
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return event.reply()
                        .withEphemeral(true)
                        .withContent("❌ Количество должно быть больше 0 (удивительно, не правда ли?): `" + spec + "`");
            }
        }

        String userId   = event.getInteraction().getUser().getId().asString();
        String username = event.getInteraction().getUser().getUsername();

        return Mono.fromCallable(() ->
                deliveryService.createTicket(resourceSpecs, location, userId, username)
        ).flatMap(ticket -> {

            // Загружаем тикет свежо из БД чтобы гарантированно получить ресурсы
            DeliveryTicket freshTicket = deliveryService.getTicketById(ticket.getId())
                    .orElse(ticket);

            log.info("Posting ticket #{} with {} resources",
                    freshTicket.getId(), freshTicket.getResources().size());

            EmbedCreateSpec embed = embedBuilder.buildTicketEmbed(freshTicket);
            List<LayoutComponent> buttons = embedBuilder.buildTicketButtons(freshTicket);

            return event.getInteraction().getChannel()
                    .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                            .addEmbed(embed)
                            .addComponent(buttons.isEmpty()
                                    ? ActionRow.of()
                                    : (LayoutComponent) buttons.get(0))
                            .build()
                    ))
                    .doOnNext(message -> {
                        deliveryService.attachMessage(
                                freshTicket.getId(),
                                message.getId().asString(),
                                message.getChannelId().asString()
                        );
                        log.info("Ticket #{} posted with {} resources",
                                freshTicket.getId(), freshTicket.getResources().size());
                    })
                    .then(event.reply()
                            .withEphemeral(true)
                            .withContent(String.format("✅ Тикет #%d создан с %d ресурсами!",
                                    freshTicket.getId(), freshTicket.getResources().size()))
                    );
        });
    }

    /**
     * /cancel-ticket id:123
     */
    public Mono<Void> handleCancelTicket(ChatInputInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(managerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("❌ Духи завода не подчинятся самозванцу!");
        }

        long ticketId = getLongOption(event, "id");

        return deliveryService.cancelTicket(ticketId)
                .map(ticket ->
                        event.reply()
                                .withEphemeral(true)
                                .withContent("✅ Тикет #" + ticketId + " отменён")
                                .then(refreshTicketMessage(ticket, event.getClient()))
                )
                .orElseGet(() -> event.reply()
                        .withEphemeral(true)
                        .withContent("❌ Тикет не найден")
                );
    }

    /**
     * /tickets — список открытых тикетов
     */
    public Mono<Void> handleListTickets(ChatInputInteractionEvent event) {
        List<DeliveryTicket> open = deliveryService.getOpenTickets();

        if (open.isEmpty()) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("📭 Активных тикетов нет");
        }

        StringBuilder sb = new StringBuilder();
        for (DeliveryTicket t : open) {
            sb.append(String.format("**#%d** 📍 %s\n", t.getId(), t.getLocation()));

            for (TicketResource r : t.getResources()) {
                int percent = r.getProgressPercent();
                String status = percent >= 100 ? "✅" : percent >= 50 ? "🟧" : "🟥";
                sb.append(String.format("  %s %s %s — %,d/%,d (%d%%)\n",
                        status,
                        r.getResourceName(),
                        r.getDeliveredAmount(),
                        r.getTargetAmount(),
                        percent));
            }
            sb.append("\n");
        }

        return event.reply()
                .withEphemeral(true)
                .withEmbeds(discord4j.core.spec.EmbedCreateSpec.builder()
                        .color(Color.of(0x3498DB))
                        .title("📋 Активные тикеты доставки")
                        .description(sb.toString())
                        .build());
    }

    /**
     * Обновить embed в Discord после изменения тикета
     */
    public Mono<Void> refreshTicketMessage(DeliveryTicket ticket, GatewayDiscordClient client) {
        if (ticket.getDiscordMessageId() == null) return Mono.empty();

        DeliveryTicket fresh = deliveryService.getTicketById(ticket.getId()).orElse(ticket);
        EmbedCreateSpec embed = embedBuilder.buildTicketEmbed(fresh);
        List<LayoutComponent> buttons = embedBuilder.buildTicketButtons(fresh);

        return client.getMessageById(
                        Snowflake.of(fresh.getDiscordChannelId()),
                        Snowflake.of(fresh.getDiscordMessageId())
                ).flatMap(message -> message.edit()
                        .withEmbeds(embed)
                        .withComponents(buttons.isEmpty()
                                ? new ArrayList<>()
                                : List.of(buttons.get(0)))
                )
                .doOnSuccess(m -> log.info("Ticket #{} updated", fresh.getId()))
                .doOnError(e -> log.error("Refresh failed: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    // Утилиты
    private String getStringOption(ChatInputInteractionEvent e, String name) {
        return e.getOption(name)
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asString)
                .orElse("");
    }

    private long getLongOption(ChatInputInteractionEvent e, String name) {
        return e.getOption(name)
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong)
                .orElse(0L);
    }
}
