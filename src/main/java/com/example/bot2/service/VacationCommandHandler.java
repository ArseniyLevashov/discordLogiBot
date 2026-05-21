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

@Component
@RequiredArgsConstructor
@Slf4j
public class VacationCommandHandler {

    private final VacationService vacationService;

    @Value("${discord.active-role-id}")
    private String activeRoleId;

    @Value("${discord.vacation-role-id}")
    private String vacationRoleId;

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
}
