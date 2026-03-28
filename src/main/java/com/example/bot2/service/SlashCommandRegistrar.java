package com.example.bot2.service;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SlashCommandRegistrar implements ApplicationRunner {

    private final GatewayDiscordClient client;

    @Override
    public void run(ApplicationArguments args) {
        long appId = client.getRestClient().getApplicationId().block();

        List<ApplicationCommandRequest> commands = List.of(

                // Создать тикет (менеджер)
                ApplicationCommandRequest.builder()
                        .name("create-ticket")
                        .description("Создать тикет на доставку ресурсов")
                        .addOption(option("resource", "Название ресурса (напр. компачи)", true))
                        .addOption(option("emoji",    "Эмодзи ресурса (напр. ⚙️)", true))
                        .addOption(optionLong("amount", "Цель в единицах (напр. 100000)", true))
                        .addOption(option("location", "Куда доставить (напр. завод/склад STRB-1)", true))
                        .build(),

                // Отменить тикет (менеджер)
                ApplicationCommandRequest.builder()
                        .name("cancel-ticket")
                        .description("Отменить тикет доставки")
                        .addOption(optionLong("id", "ID тикета", true))
                        .build(),

                // Список открытых тикетов
                ApplicationCommandRequest.builder()
                        .name("tickets")
                        .description("Показать список активных тикетов")
                        .build()
        );

        client.getRestClient().getApplicationService()
                .bulkOverwriteGlobalApplicationCommand(appId, commands)
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
