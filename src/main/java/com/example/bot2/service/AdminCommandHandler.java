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
    private String facilityManagerRoleId;

    @Value("${discord.logist-role-id}")
    private String warehouseManagerRoleId;

    @Value("${discord.ping-role-id}")
    private String pingRoleId;

    /**
     * /create-ticket resource:Железо emoji:⚙️ amount:10000 location:Склад-3
     */
    public Mono<Void> handleCreateTicket(ChatInputInteractionEvent event) {
        boolean hasFacRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)))
                .orElse(false);

        boolean hasLogiRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(warehouseManagerRoleId)))
                .orElse(false);

        if (!hasFacRole && !hasLogiRole) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("❌ Духи завода не подчиняться самозванцу!");
        }

        String location = getStringOption(event, "location");

        // Открываем Modal с полями для ресурсов
        return event.presentModal()
                .withTitle("📦 Ресурсы для доставки")
                .withCustomId("create_ticket_modal:" + location)
                .withComponents(
                        ActionRow.of(
                                TextInput.paragraph("description",
                                                "Описание задачи (необязательно)",
                                                1, 500)
                                        .required(false)
                                        .placeholder("Например: Перевезти чепуху со склада a в склад b в таком-то городе, пароль такой-то")
                        ),
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
                        )
                );
    }

    public Mono<Void> handleCreateTicketModal(ModalSubmitInteractionEvent event, String location) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)))
                .orElse(false);

        boolean hasLogiRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(warehouseManagerRoleId)))
                .orElse(false);

        if (!hasRole && !hasLogiRole) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Духи завода не подчинятся самозванцу!");
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
            return event.reply().withEphemeral(true)
                    .withContent("❌ Ну ты хоть че-нибудь напиши, дурень");
        }

        // Валидация формата
        List<String> invalid = resourceSpecs.stream()
                .filter(spec -> spec.split(":").length < 2)
                .collect(Collectors.toList());

        if (!invalid.isEmpty()) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Неверный формат: `" + String.join(", ", invalid) +
                            "`\nФормат: `название:количество` (напр. `Сальвага:10000`)");
        }

        // Валидация чисел
        for (String spec : resourceSpecs) {
            try {
                long amount = Long.parseLong(spec.split(":", 2)[1].trim());
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return event.reply().withEphemeral(true)
                        .withContent("❌ Количество должно быть больше 0 (удивительно, не правда ли?): `" + spec + "`");
            }
        }

        String description = event.getComponents(TextInput.class).stream()
                .filter(c -> c.getCustomId().equals("description"))
                .findFirst()
                .flatMap(TextInput::getValue)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(null);

        String userId   = event.getInteraction().getUser().getId().asString();
        String username = event.getInteraction().getUser().getUsername();

        final List<String> finalSpecs = resourceSpecs;
        final String finalDesc = description;

        // ← deferReply резервирует время до 15 минут вместо 3 секунд
        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() ->
                        deliveryService.createTicket(finalSpecs, location, finalDesc, userId, username)
                ))
                .flatMap(ticket -> {
                    DeliveryTicket fresh = deliveryService.getTicketById(ticket.getId()).orElse(ticket);
                    List<Object[]> top  = deliveryService.getTopContributors(fresh.getId());
                    EmbedCreateSpec embed = embedBuilder.buildTicketEmbed(fresh, top);
                    List<LayoutComponent> buttons = embedBuilder.buildTicketButtons(fresh);

                    return event.getInteraction().getChannel()
                            .flatMap(channel -> channel.createMessage(
                                    MessageCreateSpec.builder()
                                            .content("<@&" + pingRoleId + ">")
                                            .addEmbed(embed)
                                            .addComponent(buttons.isEmpty()
                                                    ? ActionRow.of()
                                                    : (LayoutComponent) buttons.get(0))
                                            .build()
                            ))
                            .doOnNext(message -> deliveryService.attachMessage(
                                    fresh.getId(),
                                    message.getId().asString(),
                                    message.getChannelId().asString()
                            ))
                            .then(event.editReply()
                                    .withContentOrNull(String.format(
                                            "✅ Тикет #%d создан с %d ресурсами!",
                                            fresh.getId(), fresh.getResources().size()))
                                    .then() // ← добавь сюда
                            );
                })
                .onErrorResume(e -> {
                    log.error("Error creating ticket: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage())
                            .then();
                })
                .then();
    }


    /**
     * /cancel-ticket id:123
     */
    public Mono<Void> handleCancelTicket(ChatInputInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)))
                .orElse(false);

        boolean hasLogiRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(warehouseManagerRoleId)))
                .orElse(false);

        if (!hasRole && !hasLogiRole) {
            return event.reply().withEphemeral(true).withContent("❌ Духи завода не подчинятся самозванцу!");
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

            // Описание если есть
            if (t.getDescription() != null && !t.getDescription().isBlank()) {
                sb.append(String.format("  📝 %s\n", t.getDescription()));
            }

            for (TicketResource r : t.getResources()) {
                int percent = r.getProgressPercent();
                String status = percent >= 100 ? "✅" : percent >= 50 ? "🟧" : "🟥";
                sb.append(String.format("  %s %s — %,d/%,d (%d%%)\n",
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
        List<Object[]> top  = deliveryService.getTopContributors(fresh.getId());
        EmbedCreateSpec embed = embedBuilder.buildTicketEmbed(fresh, top);
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
