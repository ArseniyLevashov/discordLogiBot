package com.example.bot2.service;

import com.example.bot2.entity.Warehouse;
import com.example.bot2.entity.WarehousePanel;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.InteractionCreateEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.SelectMenu;
import discord4j.core.object.component.TextInput;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    @Value("${discord.manager-role-id}")
    private String warehouseManagerRoleId;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

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
                        long h = hoursAgo(w.getLastUpdatedAt());
                        String dot = h >= 42 ? "🔴" : h >= 24 ? "🟡" : "🟢";

                        sb.append(dot).append(" **").append(w.getName()).append("**\n");
                        sb.append("  📍 ").append(w.getLocation()).append("\n");
                        sb.append("  🔑 Пароль: `").append(w.getPassword()).append("`\n"); // ← добавь
                        if (w.getDescription() != null && !w.getDescription().isBlank()) {
                            sb.append("  📝 ").append(w.getDescription()).append("\n");
                        }
                        sb.append("  🔄 ").append(formatMoscow(w.getLastUpdatedAt()))
                                .append(" МСК — **").append(h).append("ч назад**")
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

    // === Создание панели (менеджер) ===

    public Mono<Void> handleCreatePanel(ChatInputInteractionEvent event) {
        if (!isManager(event)) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> warehouseService.getAllWarehouses()))
                .flatMap(warehouses -> {
                    EmbedCreateSpec embed = buildPanelEmbed(warehouses);

                    return event.getInteraction().getChannel()
                            .flatMap(channel -> channel.createMessage(
                                    MessageCreateSpec.builder().addEmbed(embed).build()))
                            .doOnNext(msg -> warehouseService.savePanel(
                                    msg.getId().asString(),
                                    msg.getChannelId().asString()))
                            .then(event.editReply()
                                    .withContentOrNull("✅ Панель складов создана")
                                    .then());
                })
                .onErrorResume(e -> {
                    log.error("Error creating panel: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage())
                            .then();
                });
    }

    /**
     * /update-warehouse — открывает Modal для обновления (доступно всем)
     */
    public Mono<Void> handleUpdateWarehouse(ChatInputInteractionEvent event) {
        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> warehouseService.getAllWarehouses()))
                .flatMap(warehouses -> {
                    if (warehouses.isEmpty()) {
                        return event.editReply()
                                .withContentOrNull("📭 Складов пока нет").then();
                    }
                    if (warehouses.size() > 25) {
                        return event.editReply()
                                .withContentOrNull("⚠️ Слишком много складов (макс 25)").then();
                    }

                    List<SelectMenu.Option> options = warehouses.stream()
                            .map(w -> {
                                long h = hoursAgo(w.getLastUpdatedAt());
                                return SelectMenu.Option.of(
                                        w.getName() + "  (" + h + "ч назад)",
                                        w.getName());
                            })
                            .collect(Collectors.toList());

                    SelectMenu menu = SelectMenu.of("warehouse_update_select", options)
                            .withPlaceholder("Выбери склады которые обновил")
                            .withMinValues(1)
                            .withMaxValues(warehouses.size());

                    return event.editReply()
                            .withContentOrNull("Какие склады ты обновил?")
                            .withComponentsOrNull(List.of(ActionRow.of(menu)))
                            .then();
                })
                .onErrorResume(e -> event.editReply()
                        .withContentOrNull("❌ Ошибка: " + e.getMessage()).then());
    }

    /**
     * Обработка Modal обновления
     */
    public Mono<Void> handleUpdateWarehouseSelect(SelectMenuInteractionEvent event) {
        String userName = event.getInteraction().getUser().getUsername();
        List<String> selected = event.getValues();

        // Ответ эфемерный — видит только тот кто обновлял
        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() ->
                        warehouseService.updateLastUpdated(selected, userName)))
                .flatMap(result -> {
                    StringBuilder msg = new StringBuilder();
                    if (!result.getUpdated().isEmpty()) {
                        msg.append("✅ Обновлены: ")
                                .append(String.join(", ", result.getUpdated()));
                    }
                    if (!result.getNotFound().isEmpty()) {
                        msg.append("\n❌ Не найдены: ")
                                .append(String.join(", ", result.getNotFound()));
                    }

                    return event.editReply()
                            .withContentOrNull(msg.toString())
                            .then(refreshPanel(event.getClient())); // ← обновляем панель
                })
                .onErrorResume(e -> {
                    log.error("Error in select: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage()).then();
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

    // === Обновление панели в канале ===

    public Mono<Void> refreshPanel(GatewayDiscordClient client) {
        return Mono.fromCallable(() -> warehouseService.getPanel())
                .flatMap(opt -> {
                    if (opt.isEmpty()) return Mono.empty();
                    WarehousePanel panel = opt.get();

                    List<Warehouse> warehouses = warehouseService.getAllWarehouses();
                    EmbedCreateSpec embed = buildPanelEmbed(warehouses);

                    return client.getMessageById(
                                    Snowflake.of(panel.getChannelId()),
                                    Snowflake.of(panel.getMessageId()))
                            .flatMap(msg -> msg.edit().withEmbeds(embed))
                            // ← 2 повтора с задержкой при сетевом сбое
                            .retryWhen(reactor.util.retry.Retry
                                    .backoff(2, java.time.Duration.ofSeconds(3)))
                            .doOnError(e -> log.error("Panel refresh failed after retries: {}",
                                    e.getMessage()))
                            .onErrorResume(e -> Mono.empty())
                            .then();
                });
    }

    // === Построение embed панели ===

    private EmbedCreateSpec buildPanelEmbed(List<Warehouse> warehouses) {
        StringBuilder sb = new StringBuilder();

        if (warehouses.isEmpty()) {
            sb.append("*Складов пока нет*");
        } else {
            for (Warehouse w : warehouses) {
                long h = hoursAgo(w.getLastUpdatedAt());
                String dot = h >= 42 ? "🔴" : h >= 24 ? "🟡" : "🟢";

                sb.append(dot).append(" **").append(w.getName()).append("**\n");
                sb.append("  📍 ").append(w.getLocation()).append("\n");
                sb.append("  🔑 Пароль: `").append(w.getPassword()).append("`\n"); // ← добавь
                if (w.getDescription() != null && !w.getDescription().isBlank()) {
                    sb.append("  📝 ").append(w.getDescription()).append("\n");
                }
                sb.append("  🔄 ").append(formatMoscow(w.getLastUpdatedAt()))
                        .append(" МСК — **").append(h).append("ч назад**")
                        .append(" (").append(w.getLastUpdatedBy()).append(")\n\n");
            }
        }

        return EmbedCreateSpec.builder()
                .color(Color.of(0x3498DB))
                .title("📦 Состояние складов")
                .description(sb.toString())
                .footer("Последнее обновление панели: "
                        + formatMoscow(LocalDateTime.now()) + " МСК", "")
                .build();
    }

    // === Утилиты времени (МСК) ===

    /** Конвертирует серверное UTC-время в строку московского времени */
    private String formatMoscow(LocalDateTime utcTime) {
        return utcTime.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(MOSCOW)
                .format(FMT);
    }

    /** Сколько часов прошло с момента обновления */
    private long hoursAgo(LocalDateTime lastUpdated) {
        return Duration.between(lastUpdated, LocalDateTime.now()).toHours();
    }

    private boolean isManager(InteractionCreateEvent event) {
        return event.getInteraction().getMember()
                .map(m -> m.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)
                                || id.asString().equals(warehouseManagerRoleId)))
                .orElse(false);
    }

}
