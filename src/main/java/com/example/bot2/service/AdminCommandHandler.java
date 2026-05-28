package com.example.bot2.service;

import com.example.bot2.entity.DeliveryTicket;
import com.example.bot2.entity.TicketResource;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.component.LayoutComponent;
import discord4j.core.object.component.TextInput;
import discord4j.core.object.reaction.ReactionEmoji;
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

    @Value("${discord.manager-role-id}")
    private String mainFacilityManagerRoleId;

    @Value("${discord.logist-role-id}")
    private String warehouseManagerRoleId;

    @Value("${discord.ping-role-id}")
    private String pingRoleId;

    /**
     * /create-ticket resource:Железо emoji:⚙️ amount:10000 location:Склад-3
     */
    public Mono<Void> handleCreateTicket(ChatInputInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)))
                .orElse(false);

        if (!hasRole) {
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

    /**
     * /cleanup-data — открывает подтверждение
     */
    public Mono<Void> handleCleanupCommand(ChatInputInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Только администратор может удалять данные!");
        }

        Button confirmButton = Button.danger("cleanup_confirm",
                ReactionEmoji.unicode("⚠️"), "Да, удалить ВСЁ");
        Button cancelButton = Button.secondary("cleanup_cancel",
                ReactionEmoji.unicode("❌"), "Отмена");

        return event.reply()
                .withEphemeral(true)
                .withEmbeds(EmbedCreateSpec.builder()
                        .color(Color.RED)
                        .title("⚠️ ВНИМАНИЕ — удаление всех данных")
                        .description("""
                            Эта операция **полностью удалит**:
                            • Все тикеты
                            • Все ресурсы
                            • Всю историю доставок и топ доставщиков
                            
                            **Это действие НЕЛЬЗЯ отменить!**
                            
                            Сообщения тикетов в Discord останутся,
                            но кнопки на них перестанут работать.
                            Занимайся такими приколами только после окончания войны""")
                        .build())
                .withComponents(ActionRow.of(confirmButton, cancelButton));
    }

    /**
     * Кнопка подтверждения удаления
     */
    public Mono<Void> handleCleanupConfirm(ButtonInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(mainFacilityManagerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        String username = event.getInteraction().getUser().getUsername();

        return event.deferEdit()
                .then(Mono.fromCallable(() -> deliveryService.cleanupAllData()))
                .flatMap(result -> {
                    log.warn("Data cleanup performed by {}", username);

                    return event.editReply()
                            .withEmbedsOrNull(List.of(EmbedCreateSpec.builder()
                                    .color(Color.of(0x2ECC71))
                                    .title("✅ Данные удалены")
                                    .description(String.format("""
                                        Удалено:
                                        • Тикетов: **%d**
                                        • Ресурсов: **%d**
                                        • Записей о доставках: **%d**
                                        
                                        Удалил: %s""",
                                            result.getDeletedTickets(),
                                            result.getDeletedResources(),
                                            result.getDeletedContributions(),
                                            username))
                                    .build()))
                            .withComponentsOrNull(List.of()) // убираем кнопки
                            .then();
                })
                .onErrorResume(e -> {
                    log.error("Cleanup failed: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка при удалении: " + e.getMessage())
                            .then();
                });
    }

    /**
     * Кнопка отмены
     */
    public Mono<Void> handleCleanupCancel(ButtonInteractionEvent event) {
        return event.edit()
                .withEmbeds(EmbedCreateSpec.builder()
                        .color(Color.of(0x95A5A6))
                        .title("❌ Отменено")
                        .description("Данные не были удалены.")
                        .build())
                .withComponents(List.of()); // убираем кнопки
    }

    /**
     * /create-warehouse-ticket location:... — открывает Modal
     */
    public Mono<Void> handleCreateWarehouseTicket(ChatInputInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(m -> m.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)
                                || id.asString().equals(warehouseManagerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        String location = getStringOption(event, "location");

        return event.presentModal()
                .withTitle("Складская заявка")
                .withCustomId("create_wh_ticket_modal:" + location)
                .withComponents(
                        ActionRow.of(TextInput.paragraph("resources",
                                        "Предметы (название:количество)",
                                        1, 1000)
                                .required(true)
                                .placeholder("Винтовки:150\nМамонки:50\nБинты:100")),
                        ActionRow.of(TextInput.paragraph("description",
                                        "Описание (необязательно)", 1, 500)
                                .required(false))
                );
    }

    /**
     * Обработка Modal складской заявки
     */
    public Mono<Void> handleCreateWarehouseTicketModal(ModalSubmitInteractionEvent event,
                                                       String location) {
        boolean hasRole = event.getInteraction().getMember()
                .map(m -> m.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)
                                || id.asString().equals(warehouseManagerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        String rawInput = event.getComponents(TextInput.class).stream()
                .filter(c -> c.getCustomId().equals("resources"))
                .findFirst()
                .flatMap(TextInput::getValue)
                .orElse("").trim();

        String description = event.getComponents(TextInput.class).stream()
                .filter(c -> c.getCustomId().equals("description"))
                .findFirst()
                .flatMap(TextInput::getValue)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .orElse(null);

        if (rawInput.isEmpty()) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Укажи хотя бы один предмет!");
        }

        List<String> specs = Arrays.stream(rawInput.split("\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Валидация
        List<String> invalid = specs.stream()
                .filter(s -> s.split(":", 2).length < 2)
                .collect(Collectors.toList());
        if (!invalid.isEmpty()) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Неверный формат:\n`" + String.join("\n", invalid) + "`");
        }
        for (String spec : specs) {
            try {
                long amount = Long.parseLong(spec.split(":", 2)[1].trim());
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return event.reply().withEphemeral(true)
                        .withContent("❌ Неверное количество: `" + spec + "`");
            }
        }

        String userId   = event.getInteraction().getUser().getId().asString();
        String username = event.getInteraction().getUser().getUsername();
        final List<String> finalSpecs = specs;
        final String finalDesc = description;

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() ->
                        deliveryService.createTicket(finalSpecs, location, finalDesc,
                                DeliveryTicket.TicketType.WAREHOUSE,  // ← тип склада
                                userId, username)
                ))
                .flatMap(ticket -> {
                    DeliveryTicket fresh = deliveryService.getTicketById(ticket.getId()).orElse(ticket);
                    List<Object[]> top  = deliveryService.getTopContributors(fresh.getId());
                    EmbedCreateSpec embed = embedBuilder.buildTicketEmbed(fresh, top);
                    List<LayoutComponent> buttons = embedBuilder.buildTicketButtons(fresh);

                    return event.getInteraction().getChannel()
                            .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                                    .content("<@&" + pingRoleId + ">")
                                    .addEmbed(embed)
                                    .addComponent(buttons.isEmpty()
                                            ? ActionRow.of()
                                            : (LayoutComponent) buttons.get(0))
                                    .build()))
                            .doOnNext(message -> deliveryService.attachMessage(
                                    fresh.getId(),
                                    message.getId().asString(),
                                    message.getChannelId().asString()))
                            .then(event.editReply()
                                    .withContentOrNull(String.format(
                                            "✅ Складская заявка #%d создана (%d предметов)!",
                                            fresh.getId(), fresh.getResources().size()))
                                    .then());
                })
                .onErrorResume(e -> {
                    log.error("Error creating warehouse ticket: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage())
                            .then();
                });
    }

    public Mono<Void> handleCreateTicketModal(ModalSubmitInteractionEvent event, String location) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)))
                .orElse(false);

        if (!hasRole) {
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

        if (!hasRole) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        long ticketId = getLongOption(event, "id");

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> deliveryService.cancelTicket(ticketId)))
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return event.editReply()
                                .withContentOrNull("❌ Тикет не найден")
                                .then();
                    }
                    return event.editReply()
                            .withContentOrNull("✅ Тикет #" + ticketId + " отменён")
                            .then(refreshTicketMessage(opt.get(), event.getClient()))
                            .then();
                });
    }

    /**
     * /tickets — список открытых тикетов
     */
    public Mono<Void> handleListTickets(ChatInputInteractionEvent event) {
        // ← deferReply сразу
        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> deliveryService.getOpenTickets()))
                .flatMap(open -> {
                    if (open.isEmpty()) {
                        return event.editReply()
                                .withContentOrNull("📭 Активных тикетов нет")
                                .then();
                    }

                    StringBuilder sb = new StringBuilder();
                    for (DeliveryTicket t : open) {
                        sb.append(String.format("**#%d** 📍 %s\n", t.getId(), t.getLocation()));

                        if (t.getDescription() != null && !t.getDescription().isBlank()) {
                            sb.append(String.format("  📝 %s\n", t.getDescription()));
                        }

                        for (TicketResource r : t.getResources()) {
                            int percent = r.getProgressPercent();
                            String status = percent >= 100 ? "✅" : percent >= 50 ? "🟧" : "🟥";
                            sb.append(String.format("  %s %s — %,d/%,d (%d%%)\n",
                                    status, r.getResourceName(),
                                    r.getDeliveredAmount(), r.getTargetAmount(), percent));
                        }
                        sb.append("\n");
                    }

                    return event.editReply()
                            .withEmbeds(EmbedCreateSpec.builder()
                                    .color(Color.of(0x3498DB))
                                    .title("📋 Активные тикеты доставки")
                                    .description(sb.toString())
                                    .build())
                            .then();
                })
                .onErrorResume(e -> {
                    log.error("Error listing tickets: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage())
                            .then();
                });
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
