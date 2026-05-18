package com.example.bot2.service;

import com.example.bot2.entity.Warehouse;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.TextInput;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class WarehouseCommandHandler {

    private final WarehouseService warehouseService;

    @Value("${discord.manager-role-id}")
    private String facilityManagerRoleId;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * /create-warehouse — открывает Modal (только для менеджеров)
     */
    public Mono<Void> handleCreateWarehouse(ChatInputInteractionEvent event) {
        if (!hasManagerRole(event)) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Нет прав для создания складов!");
        }

        return event.presentModal()
                .withTitle("Создать склад")
                .withCustomId("create_warehouse_modal")
                .withComponents(
                        ActionRow.of(TextInput.small("name", "Имя склада", 1, 100)
                                .required(true)),
                        ActionRow.of(TextInput.small("location", "Расположение", 1, 200)
                                .required(true)
                                .placeholder("Координаты или название региона")),
                        ActionRow.of(TextInput.paragraph("description", "Описание", 1, 1000)
                                .required(false)),
                        ActionRow.of(TextInput.small("password", "Пароль", 1, 100)
                                .required(true))
                );
    }

    /**
     * Обработка Modal создания
     */
    public Mono<Void> handleCreateWarehouseModal(ModalSubmitInteractionEvent event) {
        if (!hasManagerRole(event)) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        String name = getInputValue(event, "name");
        String location = getInputValue(event, "location");
        String description = getInputValue(event, "description");
        String password = getInputValue(event, "password");
        String userName = event.getInteraction().getUser().getUsername();

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() ->
                        warehouseService.createWarehouse(name, location, description, password, userName)
                ))
                .flatMap(w -> event.editReply()
                        .withEmbedsOrNull(List.of(buildWarehouseEmbed(w, "Склад создан")))
                        .then())
                .onErrorResume(e -> {
                    log.error("Error creating warehouse: {}", e.getMessage());
                    return event.editReply()
                            .withContentOrNull("❌ " + e.getMessage())
                            .then();
                });
    }

    /**
     * /warehouses — список складов
     */
    public Mono<Void> handleListWarehouses(ChatInputInteractionEvent event) {
        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> warehouseService.getAllWarehouses()))
                .flatMap(warehouses -> {
                    if (warehouses.isEmpty()) {
                        return event.editReply()
                                .withContentOrNull("📭 Складов пока нет")
                                .then();
                    }

                    StringBuilder sb = new StringBuilder();
                    for (Warehouse w : warehouses) {
                        sb.append("🏭 **").append(w.getName()).append("**\n");
                        sb.append("  📍 Расположение: ").append(w.getLocation()).append("\n");
                        sb.append("  🔑 Пароль: `").append(w.getPassword()).append("`\n");

                        if (w.getDescription() != null && !w.getDescription().isBlank()) {
                            sb.append("  📝 ").append(w.getDescription()).append("\n");
                        }
                        sb.append("  📅 Создан: ").append(w.getCreatedAt().format(FMT))
                                .append(" (").append(w.getCreatedBy()).append(")\n");
                        sb.append("  🔄 Обновлён: ").append(w.getLastUpdatedAt().format(FMT))
                                .append(" (").append(w.getLastUpdatedBy()).append(")\n\n");
                    }

                    return event.editReply()
                            .withEmbedsOrNull(List.of(EmbedCreateSpec.builder()
                                    .color(Color.of(0x3498DB))
                                    .title("Список складов")
                                    .description(sb.toString())
                                    .build()))
                            .then();
                })
                .onErrorResume(e -> {
                    log.error("Error listing warehouses: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage())
                            .then();
                });
    }

    /**
     * /update-warehouse — открывает Modal для обновления (доступно всем)
     */
    public Mono<Void> handleUpdateWarehouse(ChatInputInteractionEvent event) {
        return event.presentModal()
                .withTitle("Обновить склады")
                .withCustomId("update_warehouse_modal")
                .withComponents(
                        ActionRow.of(TextInput.paragraph("names",
                                        "Названия складов (каждое с новой строки)",
                                        1, 1000)
                                .required(true)
                                .placeholder("СТРБ-1\nКаллумс\nПорт"))
                );
    }

    /**
     * Обработка Modal обновления
     */
    public Mono<Void> handleUpdateWarehouseModal(ModalSubmitInteractionEvent event) {
        String userName = event.getInteraction().getUser().getUsername();
        String rawNames = getInputValue(event, "names");

        if (rawNames.isEmpty()) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Укажи хотя бы один склад!");
        }

        List<String> names = Arrays.stream(rawNames.split("\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        return event.deferReply().withEphemeral(false)
                .then(Mono.fromCallable(() ->
                        warehouseService.updateLastUpdated(names, userName)
                ))
                .flatMap(result -> {
                    StringBuilder msg = new StringBuilder();
                    if (!result.getUpdated().isEmpty()) {
                        msg.append("✅ **Обновлены:**\n");
                        for (String n : result.getUpdated()) msg.append("  • ").append(n).append("\n");
                    }
                    if (!result.getNotFound().isEmpty()) {
                        if (msg.length() > 0) msg.append("\n");
                        msg.append("❌ **Не найдены:**\n");
                        for (String n : result.getNotFound()) msg.append("  • ").append(n).append("\n");
                    }

                    return event.editReply()
                            .withEmbedsOrNull(List.of(EmbedCreateSpec.builder()
                                    .color(result.getNotFound().isEmpty()
                                            ? Color.of(0x2ECC71) : Color.of(0xF39C12))
                                    .title("Обновление складов от " + userName)
                                    .description(msg.toString())
                                    .build()))
                            .then();
                })
                .onErrorResume(e -> {
                    log.error("Error updating warehouses: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage())
                            .then();
                });
    }

    /**
     * /delete-warehouse name:Завод-3
     */
    public Mono<Void> handleDeleteWarehouse(ChatInputInteractionEvent event) {
        if (!hasManagerRole(event)) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        String name = event.getOption("name")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asString)
                .orElse("");

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> warehouseService.deleteWarehouse(name)))
                .flatMap(deleted -> event.editReply()
                        .withContentOrNull(deleted
                                ? "✅ Склад '" + name + "' удалён"
                                : "❌ Склад '" + name + "' не найден")
                        .then());
    }

    // --- Утилиты ---

    private boolean hasManagerRole(discord4j.core.event.domain.interaction.InteractionCreateEvent event) {
        return event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)))
                .orElse(false);
    }

    private EmbedCreateSpec buildWarehouseEmbed(Warehouse w, String title) {
        StringBuilder desc = new StringBuilder();
        desc.append("📍 Расположение: ").append(w.getLocation()).append("\n");
        desc.append("🔑 Пароль: `").append(w.getPassword()).append("`\n");
        if (w.getDescription() != null && !w.getDescription().isBlank()) {
            desc.append("📝 ").append(w.getDescription()).append("\n");
        }
        desc.append("📅 Создан: ").append(w.getCreatedAt().format(FMT));

        return EmbedCreateSpec.builder()
                .color(Color.of(0x2ECC71))
                .title(title + ": " + w.getName())
                .description(desc.toString())
                .build();
    }

    private String getInputValue(ModalSubmitInteractionEvent event, String fieldId) {
        return event.getComponents(TextInput.class).stream()
                .filter(c -> c.getCustomId().equals(fieldId))
                .findFirst()
                .flatMap(TextInput::getValue)
                .map(String::trim)
                .orElse("");
    }
}
