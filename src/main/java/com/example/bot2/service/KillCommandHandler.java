package com.example.bot2.service;

import com.example.bot2.entity.EquipmentCategory;
import com.example.bot2.entity.EquipmentKill;
import com.example.bot2.entity.EquipmentType;
import com.example.bot2.entity.KillPanel;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.*;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.component.SelectMenu;
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
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class KillCommandHandler {

    private final KillService killService;

    @Value("${discord.facility-manager-role-id:0}")
    private String adminRoleId;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    // ============= АДМИНСКИЕ КОМАНДЫ =============

    /** /create-kill-panel — создать новую панель в текущем канале */
    public Mono<Void> handleCreatePanel(ChatInputInteractionEvent event) {
        if (!isAdmin(event)) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> killService.createNewPanel()))
                .flatMap(panel -> {
                    EmbedCreateSpec embed = buildPanelEmbed(panel);
                    Button addBtn = Button.primary("add_kill_button",
                            ReactionEmoji.unicode("✏️"), "Добавить результат");

                    return event.getInteraction().getChannel()
                            .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                                    .addEmbed(embed)
                                    .addComponent(ActionRow.of(addBtn))
                                    .build()))
                            .doOnNext(msg -> killService.attachMessage(
                                    panel.getId(),
                                    msg.getId().asString(),
                                    msg.getChannelId().asString()))
                            .then(event.editReply()
                                    .withContentOrNull("✅ Новая панель создана (#" + panel.getId() + ")")
                                    .then());
                })
                .onErrorResume(e -> event.editReply()
                        .withContentOrNull("❌ " + e.getMessage()).then());
    }

    /** /close-kill-panel — закрыть подсчёт, удалить записи */
    public Mono<Void> handleClosePanel(ChatInputInteractionEvent event) {
        if (!isAdmin(event)) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> killService.closeActivePanel()))
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return event.editReply()
                                .withContentOrNull("❌ Нет активной панели").then();
                    }
                    KillPanel panel = opt.get();
                    // Убираем кнопку с сообщения закрытой панели
                    return event.getClient()
                            .getMessageById(Snowflake.of(panel.getChannelId()),
                                    Snowflake.of(panel.getMessageId()))
                            .flatMap(msg -> msg.edit().withComponents(List.of()))
                            .onErrorResume(e -> Mono.empty())
                            .then(event.editReply()
                                    .withContentOrNull("✅ Подсчёт закрыт, данные очищены").then());
                });
    }

    /** /edit-kill id:X — открыть modal с текущим значением */
    public Mono<Void> handleEditKill(ChatInputInteractionEvent event) {
        if (!isAdmin(event)) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }
        long killId = event.getOption("id")
                .flatMap(o -> o.getValue()).map(v -> v.asLong()).orElse(0L);

        Optional<EquipmentKill> opt = killService.getKill(killId);
        if (opt.isEmpty()) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Запись #" + killId + " не найдена");
        }
        EquipmentKill k = opt.get();

        return event.presentModal()
                .withTitle("Редактирование #" + killId)
                .withCustomId("edit_kill_modal:" + killId)
                .withComponents(ActionRow.of(
                        TextInput.small("amount",
                                        k.getEquipmentType().getName() + " — кол-во (0 = удалить)",
                                        1, 6)
                                .required(true)
                                .prefilled(String.valueOf(k.getAmount()))
                ));
    }

    public Mono<Void> handleEditKillModal(ModalSubmitInteractionEvent event, Long killId) {
        if (!isAdmin(event)) {
            return event.reply().withEphemeral(true).withContent("❌ Нет прав!");
        }
        String raw = inputValue(event, "amount");
        int amount;
        try { amount = Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) {
            return event.reply().withEphemeral(true).withContent("❌ Не число");
        }

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> {
                    if (amount == 0) {
                        killService.deleteKill(killId);
                        return "🗑️ Запись #" + killId + " удалена";
                    }
                    killService.editKill(killId, amount);
                    return "✅ Запись #" + killId + " → " + amount;
                }))
                .flatMap(msg -> event.editReply()
                        .withContentOrNull(msg)
                        .then(refreshPanel(event.getClient())));
    }

    // ============= ПОЛЬЗОВАТЕЛЬСКИЙ ФЛОУ =============

    /** Кнопка "Добавить результат" → выбор категории */
    public Mono<Void> handleAddKillButton(ButtonInteractionEvent event) {
        List<EquipmentCategory> cats = killService.getCategories();
        if (cats.isEmpty()) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Категории техники не заполнены в БД");
        }

        List<SelectMenu.Option> opts = cats.stream()
                .map(c -> SelectMenu.Option.of(c.getName(), c.getId().toString()))
                .collect(Collectors.toList());

        SelectMenu menu = SelectMenu.of("kill_category_select", opts)
                .withPlaceholder("Выбери категорию");

        return event.reply().withEphemeral(true)
                .withContent("Шаг 1/3 — категория техники:")
                .withComponents(ActionRow.of(menu));
    }

    /** Выбор категории → показ типов */
    public Mono<Void> handleCategorySelect(SelectMenuInteractionEvent event) {
        Long catId = Long.parseLong(event.getValues().get(0));
        List<EquipmentType> types = killService.getTypesByCategory(catId);

        if (types.isEmpty()) {
            return event.edit()
                    .withContent("❌ В этой категории нет техники")
                    .withComponents(List.of());
        }

        List<SelectMenu.Option> opts = types.stream()
                .map(t -> SelectMenu.Option.of(t.getName(), t.getId().toString()))
                .collect(Collectors.toList());

        SelectMenu menu = SelectMenu.of("kill_type_select", opts)
                .withPlaceholder("Выбери технику");

        return event.edit()
                .withContent("Шаг 2/3 — конкретная техника:")
                .withComponents(List.of(ActionRow.of(menu)));
    }

    /** Выбор техники → modal для количества */
    public Mono<Void> handleTypeSelect(SelectMenuInteractionEvent event) {
        Long typeId = Long.parseLong(event.getValues().get(0));
        return event.presentModal()
                .withTitle("Количество уничтоженного")
                .withCustomId("kill_amount_modal:" + typeId)
                .withComponents(ActionRow.of(
                        TextInput.small("amount", "Сколько уничтожил?", 1, 6)
                                .required(true)
                                .placeholder("1")
                ));
    }

    /** Сабмит модалки → запись + рефреш панели */
    public Mono<Void> handleAmountModal(ModalSubmitInteractionEvent event, Long typeId) {
        String raw = inputValue(event, "amount");
        int amount;
        try { amount = Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) {
            return event.reply().withEphemeral(true).withContent("❌ Введи число");
        }
        if (amount <= 0) {
            return event.reply().withEphemeral(true).withContent("❌ Больше 0!");
        }

        String userId   = event.getInteraction().getUser().getId().asString();
        String username = event.getInteraction().getUser().getUsername();
        final int finalAmount = amount;

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() ->
                        killService.recordKill(typeId, finalAmount, userId, username)))
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return event.editReply()
                                .withContentOrNull("❌ Нет активной панели или техника не найдена")
                                .then();
                    }
                    EquipmentKill k = opt.get();
                    return event.editReply()
                            .withContentOrNull(String.format("✅ Записано: %s × %d (#%d)",
                                    k.getEquipmentType().getName(), k.getAmount(), k.getId()))
                            .then(refreshPanel(event.getClient()));
                });
    }

    // ============= ОБНОВЛЕНИЕ ПАНЕЛИ =============

    public Mono<Void> refreshPanel(GatewayDiscordClient client) {
        return Mono.fromCallable(() -> killService.getActivePanel())
                .flatMap(opt -> {
                    if (opt.isEmpty()) return Mono.empty();
                    KillPanel panel = opt.get();
                    EmbedCreateSpec embed = buildPanelEmbed(panel);
                    return client.getMessageById(
                                    Snowflake.of(panel.getChannelId()),
                                    Snowflake.of(panel.getMessageId()))
                            .flatMap(msg -> msg.edit().withEmbeds(embed))
                            .retryWhen(Retry.backoff(2, Duration.ofSeconds(2)))
                            .onErrorResume(e -> {
                                log.error("Kill panel refresh failed: {}", e.getMessage());
                                return Mono.empty();
                            })
                            .then();
                });
    }

    private EmbedCreateSpec buildPanelEmbed(KillPanel panel) {
        Map<Long, Long> totals = killService.getTotalsByType(panel.getId());
        List<EquipmentCategory> categories = killService.getCategories();
        List<EquipmentKill> last5 = killService.getLast5(panel.getId());

        EmbedCreateSpec.Builder builder = EmbedCreateSpec.builder()
                .color(Color.of(0xC0392B))
                .title("💥 Уничтоженная техника — панель #" + panel.getId());

        long grandTotal = 0;
        for (EquipmentCategory cat : categories) {
            List<EquipmentType> types = killService.getTypesByCategory(cat.getId());
            StringBuilder field = new StringBuilder();
            long catTotal = 0;
            for (EquipmentType t : types) {
                long count = totals.getOrDefault(t.getId(), 0L);
                catTotal += count;
                field.append("• ").append(t.getName()).append(" — **").append(count).append("**\n");
            }
            if (field.length() == 0) field.append("*пусто*");
            grandTotal += catTotal;
            builder.addField(cat.getName() + " (всего: " + catTotal + ")",
                    field.toString(), true);
        }

        // Последние 5 записей
        StringBuilder recent = new StringBuilder();
        if (last5.isEmpty()) {
            recent.append("*пока никто не записывал*");
        } else {
            for (EquipmentKill k : last5) {
                String time = k.getCreatedAt().atZone(ZoneOffset.UTC)
                        .withZoneSameInstant(MOSCOW).format(FMT);
                recent.append("`#").append(k.getId()).append("` ")
                        .append(k.getEquipmentType().getName())
                        .append(" × **").append(k.getAmount()).append("** — ")
                        .append(k.getUsername()).append(" (").append(time).append(")\n");
            }
        }
        builder.addField("📋 Последние 5 записей", recent.toString(), false);

        builder.footer("Всего уничтожено единиц: " + grandTotal, "");
        return builder.build();
    }

    // ============= УТИЛИТЫ =============

    private boolean isAdmin(InteractionCreateEvent event) {
        return event.getInteraction().getMember()
                .map(m -> m.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(adminRoleId)))
                .orElse(false);
    }

    private String inputValue(ModalSubmitInteractionEvent event, String id) {
        return event.getComponents(TextInput.class).stream()
                .filter(c -> c.getCustomId().equals(id))
                .findFirst()
                .flatMap(TextInput::getValue)
                .orElse("");
    }
}
