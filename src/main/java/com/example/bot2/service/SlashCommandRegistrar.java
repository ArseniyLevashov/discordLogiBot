package com.example.bot2.service;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SlashCommandRegistrar implements ApplicationRunner {

    private final GatewayDiscordClient client;

    @Value("${discord.guild-id}")
    private long guildId;

    @Override
    public void run(ApplicationArguments args) {
        long appId = client.getRestClient().getApplicationId().block();

        List<ApplicationCommandRequest> commands = List.of(
                ApplicationCommandRequest.builder()
                        .name("create-ticket")
                        .description("Создать тикет на доставку ресурсов")
                        .addOption(option("location", "Куда доставить", true))
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("cancel-ticket")
                        .description("Отменить тикет доставки")
                        .addOption(optionLong("id", "ID тикета", true))
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("tickets")
                        .description("Показать список активных тикетов")
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("cleanup-data")
                        .description("⚠️ Удалить ВСЕ тикеты, ресурсы и историю доставок")
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("warehouse-panel")
                        .description("Создать постоянную панель состояния складов")
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("create-warehouse")
                        .description("Создать новый склад")
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("warehouses")
                        .description("Показать список всех складов")
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("update-warehouse")
                        .description("Обновить дату последнего обновления склада/складов")
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("delete-warehouse")
                        .description("Удалить склад")
                        .addOption(option("name", "Название склада", true))
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("vacation-panel")
                        .description("Создать панель с кнопкой ухода в отпуск")
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("vacations")
                        .description("Показать список всех активных отпусков")
                        .build(),

                ApplicationCommandRequest.builder()
                        .name("end-vacation")
                        .description("Снять отпуск вручную по ID")
                        .addOption(optionLong("id", "ID отпуска (из списка /vacations)", true))
                        .build()
        );

        // Глобальные — обновляются до 1 часа
        // client.getRestClient().getApplicationService()
        //         .bulkOverwriteGlobalApplicationCommand(appId, commands)
        //         .blockLast();

        // Для конкретного сервера — обновляются мгновенно ✅
        client.getRestClient().getApplicationService()
                .bulkOverwriteGuildApplicationCommand(appId, guildId, commands)
                .blockLast();
    }

    private ApplicationCommandOptionData option(String name, String desc, boolean required) {
        return ApplicationCommandOptionData.builder()
                .name(name).description(desc).required(required)
                .type(ApplicationCommandOption.Type.STRING.getValue())
                .build();
    }

    private ApplicationCommandOptionData optionLong(String name, String desc, boolean required) {
        return ApplicationCommandOptionData.builder()
                .name(name).description(desc).required(required)
                .type(ApplicationCommandOption.Type.INTEGER.getValue())
                .build();
    }
}
