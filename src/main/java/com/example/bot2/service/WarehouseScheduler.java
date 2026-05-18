package com.example.bot2.service;

import com.example.bot2.entity.Warehouse;
import com.example.bot2.repository.WarehouseRepository;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WarehouseScheduler {

    private final WarehouseRepository warehouseRepo;
    private final GatewayDiscordClient client;

    @Value("${discord.stale-warehouse-channel-id}")
    private String channelId;

    @Value("${discord.stale-warehouse-role-id}")
    private String roleId;

    @Value("${warehouse.stale-hours:42}")
    private int staleHours;

    /**
     * Каждый час проверяем устаревшие склады
     */
    @Scheduled(fixedRate = 3600000, initialDelay = 60000) // раз в час, первый запуск через минуту
    public void checkStaleWarehouses() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(staleHours);
        List<Warehouse> stale = warehouseRepo.findByLastUpdatedAtBefore(threshold);

        if (stale.isEmpty()) {
            log.debug("No stale warehouses found");
            return;
        }

        log.info("Found {} stale warehouses, sending notification", stale.size());

        StringBuilder sb = new StringBuilder();
        for (Warehouse w : stale) {
            long hoursAgo = Duration.between(w.getLastUpdatedAt(), LocalDateTime.now()).toHours();
            sb.append("• **").append(w.getName()).append("**")
                    .append(" — обновлён **").append(hoursAgo).append("ч** назад\n");
        }

        EmbedCreateSpec embed = EmbedCreateSpec.builder()
                .color(Color.of(0xE74C3C))
                .title("⚠️ Внимание! Склады требуют обновления")
                .description(String.format("Не обновлялись более **%d часов**:\n\n%s",
                        staleHours, sb))
                .build();

        client.getChannelById(Snowflake.of(channelId))
                .ofType(MessageChannel.class)
                .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                        .content("<@&" + roleId + ">")
                        .addEmbed(embed)
                        .build()))
                .doOnSuccess(m -> log.info("Stale warehouse notification sent"))
                .doOnError(e -> log.error("Failed to send notification: {}", e.getMessage()))
                .subscribe();
    }
}
