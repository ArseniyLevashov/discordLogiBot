package com.example.bot2.service;

import com.example.bot2.entity.Vacation;
import discord4j.common.util.Snowflake;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.ModalSubmitInteractionEvent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.component.TextInput;
import discord4j.core.object.entity.Member;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class VacationCommandHandler {

    private final VacationService vacationService;

    @Value("${discord.active-role-id}")
    private String activeRoleId;

    @Value("${discord.vacation-role-id}")
    private String vacationRoleId;

    @Value("${discord.facility-manager-role-id}")
    private String facilityManagerRoleId;

    @Value("${discord.warehouse-manager-role-id}")
    private String warehouseManagerRoleId;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * /vacation-panel — постит кнопку "Уйти в отпуск" (для всех в канале)
     */
    public Mono<Void> handleVacationPanel(ChatInputInteractionEvent event) {
        Button vacationButton = Button.primary("vacation_request",
                ReactionEmoji.unicode("🏖️"), "Уйти в отпуск");

        EmbedCreateSpec embed = EmbedCreateSpec.builder()
                .color(Color.of(0x3498DB))
                .title("🏖️ Оформление отпуска")
                .description("Нажми кнопку чтобы оформить отпуск.\n" +
                        "Тебе нужно будет указать дату окончания.")
                .build();

        return event.getInteraction().getChannel()
                .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                        .addEmbed(embed)
                        .addComponent(ActionRow.of(vacationButton))
                        .build()))
                .then(event.reply()
                        .withEphemeral(true)
                        .withContent("✅ Панель отпуска создана"));
    }

    /**
     * Кнопка "Уйти в отпуск" — открывает Modal с датой
     */
    public Mono<Void> handleVacationButton(ButtonInteractionEvent event) {
        return event.presentModal()
                .withTitle("Оформление отпуска")
                .withCustomId("vacation_modal")
                .withComponents(ActionRow.of(
                        TextInput.small("end_date", "До какой даты (ДД.ММ.ГГГГ)", 10, 10)
                                .required(true)
                                .placeholder("25.06.2026")
                ));
    }

    /**
     * Обработка Modal — снимаем рабочую роль, выдаём отпускную
     */
    public Mono<Void> handleVacationModal(ModalSubmitInteractionEvent event) {
        String rawDate = event.getComponents(TextInput.class).stream()
                .filter(c -> c.getCustomId().equals("end_date"))
                .findFirst()
                .flatMap(TextInput::getValue)
                .map(String::trim)
                .orElse("");

        LocalDate endDate;
        try {
            endDate = LocalDate.parse(rawDate, FMT);
        } catch (DateTimeParseException e) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Неверный формат даты. Используй ДД.ММ.ГГГГ (напр. 25.06.2026)");
        }

        if (endDate.isBefore(LocalDate.now()) || endDate.isEqual(LocalDate.now())) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Дата окончания должна быть в будущем!");
        }

        String userId    = event.getInteraction().getUser().getId().asString();
        String username  = event.getInteraction().getUser().getUsername();
        String guildId   = event.getInteraction().getGuildId()
                .map(Snowflake::asString).orElse("");
        String channelId = event.getInteraction().getChannelId().asString();
        final LocalDate finalDate = endDate;

        return event.deferReply().withEphemeral(true)
                .then(event.getInteraction().getMember()
                        .map(Mono::just)
                        .orElse(Mono.empty()))
                .flatMap(member ->
                        // Снимаем рабочую роль, выдаём отпускную
                        member.removeRole(Snowflake.of(activeRoleId), "Уход в отпуск")
                                .then(member.addRole(Snowflake.of(vacationRoleId), "Уход в отпуск"))
                                .then(Mono.fromCallable(() ->
                                        vacationService.createVacation(
                                                userId, username, guildId, channelId, finalDate)
                                ))
                                .then(event.editReply()
                                        .withContentOrNull(String.format(
                                                "🏖️ Отпуск оформлен до **%s**!\n" +
                                                        "Роли вернутся автоматически в 19:00 по МСК в день окончания.",
                                                finalDate.format(FMT)))
                                        .then())
                )
                .onErrorResume(e -> {
                    log.error("Error creating vacation: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage() +
                                    "\nПроверь что у бота есть права управлять ролями.")
                            .then();
                });
    }

    /**
     * /vacations — список всех активных отпусков (только менеджер)
     */
    public Mono<Void> handleListVacations(ChatInputInteractionEvent event) {
        boolean isManager = event.getInteraction().getMember()
                .map(m -> m.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)
                                || id.asString().equals(warehouseManagerRoleId)))
                .orElse(false);

        if (!isManager) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Только менеджер может просматривать отпуска!");
        }

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> vacationService.getActiveVacations()))
                .flatMap(vacations -> {
                    if (vacations.isEmpty()) {
                        return event.editReply()
                                .withContentOrNull("📭 Активных отпусков нет")
                                .then();
                    }

                    StringBuilder sb = new StringBuilder();
                    for (Vacation v : vacations) {
                        long daysLeft = v.getEndDate().toEpochDay() - LocalDate.now().toEpochDay();
                        sb.append("🏖️ **").append(v.getUsername()).append("**\n")
                                .append("  📅 До: ").append(v.getEndDate().format(FMT))
                                .append(" (осталось ").append(daysLeft).append(" дн.)\n")
                                .append("  🔧 Снять: `/end-vacation id:").append(v.getId()).append("`\n\n");
                    }

                    return event.editReply()
                            .withEmbedsOrNull(List.of(EmbedCreateSpec.builder()
                                    .color(Color.of(0x3498DB))
                                    .title("🏖️ Активные отпуска (" + vacations.size() + ")")
                                    .description(sb.toString())
                                    .build()))
                            .then();
                })
                .onErrorResume(e -> {
                    log.error("Error listing vacations: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage())
                            .then();
                });
    }

    /**
     * /end-vacation id:5 — снять отпуск вручную (только менеджер)
     */
    public Mono<Void> handleEndVacation(ChatInputInteractionEvent event) {
        boolean isManager = event.getInteraction().getMember()
                .map(m -> m.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(facilityManagerRoleId)
                                || id.asString().equals(warehouseManagerRoleId)))
                .orElse(false);

        if (!isManager) {
            return event.reply().withEphemeral(true)
                    .withContent("❌ Только менеджер может снимать отпуска!");
        }

        long vacationId = event.getOption("id")
                .flatMap(o -> o.getValue())
                .map(v -> v.asLong())
                .orElse(0L);

        return event.deferReply().withEphemeral(true)
                .then(Mono.fromCallable(() -> vacationService.getById(vacationId)))
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return event.editReply()
                                .withContentOrNull("❌ Отпуск #" + vacationId + " не найден")
                                .then();
                    }

                    Vacation vacation = opt.get();
                    Snowflake guildId = Snowflake.of(vacation.getGuildId());
                    Snowflake userId  = Snowflake.of(vacation.getUserId());

                    return event.getClient().getMemberById(guildId, userId)
                            .flatMap(member ->
                                    member.removeRole(Snowflake.of(vacationRoleId), "Отпуск снят менеджером")
                                            .then(member.addRole(Snowflake.of(activeRoleId), "Отпуск снят менеджером"))
                            )
                            .then(Mono.fromRunnable(() -> vacationService.deleteVacation(vacation)))
                            // Уведомление в канал откуда был оформлен отпуск
                            .then(event.getClient().getChannelById(Snowflake.of(vacation.getChannelId()))
                                    .ofType(discord4j.core.object.entity.channel.MessageChannel.class)
                                    .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                                            .content("<@" + vacation.getUserId() + ">")
                                            .addEmbed(EmbedCreateSpec.builder()
                                                    .color(Color.of(0xF39C12))
                                                    .title("🏖️ Отпуск завершён досрочно")
                                                    .description(String.format(
                                                            "Отпуск пользователя **%s** снят менеджером.\n" +
                                                                    "Роли возвращены.",
                                                            vacation.getUsername()))
                                                    .build())
                                            .build()))
                            )
                            .then(event.editReply()
                                    .withContentOrNull("✅ Отпуск #" + vacationId +
                                            " (" + vacation.getUsername() + ") снят, роли возвращены")
                                    .then());
                })
                .onErrorResume(e -> {
                    log.error("Error ending vacation: ", e);
                    return event.editReply()
                            .withContentOrNull("❌ Ошибка: " + e.getMessage())
                            .then();
                });
    }
}
