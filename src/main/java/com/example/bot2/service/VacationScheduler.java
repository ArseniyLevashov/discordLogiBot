package com.example.bot2.service;

import com.example.bot2.entity.Vacation;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.spec.MessageCreateSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class VacationScheduler {

    private final VacationService vacationService;
    private final GatewayDiscordClient client;

    @Value("${discord.active-role-id}")
    private String activeRoleId;

    @Value("${discord.vacation-role-id}")
    private String vacationRoleId;

    /**
     * Каждый день в 19:00 по Москве проверяем закончившиеся отпуска
     */
    @Scheduled(cron = "0 0 19 * * *", zone = "Europe/Moscow")
    public void checkEndedVacations() {
        List<Vacation> ended = vacationService.getEndedVacations();

        if (ended.isEmpty()) {
            log.debug("No ended vacations today");
            return;
        }

        log.info("Found {} ended vacations", ended.size());

        for (Vacation vacation : ended) {
            processEndedVacation(vacation).subscribe();
        }
    }

    private Mono<Void> processEndedVacation(Vacation vacation) {
        Snowflake guildId   = Snowflake.of(vacation.getGuildId());
        Snowflake userId    = Snowflake.of(vacation.getUserId());
        Snowflake channelId = Snowflake.of(vacation.getChannelId());

        return client.getMemberById(guildId, userId)
                // Снимаем отпускную роль, возвращаем рабочую
                .flatMap(member -> member.removeRole(Snowflake.of(vacationRoleId), "Конец отпуска")
                        .then(member.addRole(Snowflake.of(activeRoleId), "Конец отпуска"))
                )
                // Отмечаем отпуск завершённым в БД
                .then(Mono.fromRunnable(() -> vacationService.markCompleted(vacation)))
                // Отправляем уведомление в тот же канал
                .then(client.getChannelById(channelId)
                        .ofType(MessageChannel.class)
                        .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                                .content("<@" + vacation.getUserId() + ">")
                                .addEmbed(discord4j.core.spec.EmbedCreateSpec.builder()
                                        .color(discord4j.rest.util.Color.of(0x2ECC71))
                                        .title("🏖️ Отпуск окончен!")
                                        .description(String.format(
                                                "Отпуск пользователя **%s** завершён.\n" +
                                                        "Роли возвращены в исходное состояние.",
                                                vacation.getUsername()))
                                        .build())
                                .build()))
                )
                .doOnSuccess(m -> log.info("Vacation ended for user {}", vacation.getUsername()))
                .doOnError(e -> log.error("Failed to end vacation for {}: {}",
                        vacation.getUsername(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }
}
