package com.example.bot2.service;

import com.example.bot2.entity.DeliveryTicket;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.LayoutComponent;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminCommandHandler {

    private final DeliveryService deliveryService;
    private final EmbedBuilder embedBuilder;

    @Value("${discord.manager-role-id}")
    private String managerRoleId;

    /**
     * /create-ticket resource:Железо emoji:⚙️ amount:10000 location:Склад-3
     */
    public Mono<Void> handleCreateTicket(ChatInputInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(managerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("❌ Дух завода не подчинится самозванцу!");
        }

        return doCreateTicket(event);
    }

    private Mono<Void> doCreateTicket(ChatInputInteractionEvent event) {
        String resource = getStringOption(event, "resource");
        String emoji    = getStringOption(event, "emoji");
        long amount     = getLongOption(event, "amount");
        String location = getStringOption(event, "location");
        String userId   = event.getInteraction().getUser().getId().asString();
        String username = event.getInteraction().getUser().getUsername();

        DeliveryTicket ticket = deliveryService.createTicket(
                resource, emoji, amount, location, userId, username);

        EmbedCreateSpec embed = embedBuilder.buildTicketEmbed(ticket, List.of());
        List<LayoutComponent> buttons = embedBuilder.buildTicketButtons(ticket);

        // Получаем канал из самого interaction и постим обычное сообщение
        return event.getInteraction().getChannel()
                .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                        .addEmbed(embed)
                        .addComponent(buttons.isEmpty()
                                ? ActionRow.of()
                                : (LayoutComponent) buttons.get(0))
                        .build()
                ))
                .doOnNext(message -> {
                    log.info("Ticket #{} posted as message {}", ticket.getId(), message.getId().asString());
                    deliveryService.attachMessage(
                            ticket.getId(),
                            message.getId().asString(),
                            message.getChannelId().asString()
                    );
                })
                .then(
                        // Отвечаем на slash-команду эфемерно чтобы она не висела
                        event.reply()
                                .withEphemeral(true)
                                .withContent("✅ Тикет #" + ticket.getId() + " создан!")
                );
    }

    /**
     * /cancel-ticket id:123
     */
    public Mono<Void> handleCancelTicket(ChatInputInteractionEvent event) {
        boolean hasRole = event.getInteraction().getMember()
                .map(member -> member.getRoleIds().stream()
                        .anyMatch(id -> id.asString().equals(managerRoleId)))
                .orElse(false);

        if (!hasRole) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("❌ Дух завода не подчинится самозванцу!");
        }

        long ticketId = getLongOption(event, "id");

        return deliveryService.cancelTicket(ticketId)
                .map(ticket ->
                        event.reply()
                                .withEphemeral(true)
                                .withContent("✅ Тикет #" + ticketId + " отменён")
                                .then(refreshTicketMessage(ticket, event.getClient()))
                )
                .orElseGet(() -> event.reply()
                        .withEphemeral(true)
                        .withContent("❌ Тикет не найден")
                );
    }

    /**
     * /tickets — список открытых тикетов
     */
    public Mono<Void> handleListTickets(ChatInputInteractionEvent event) {
        List<DeliveryTicket> open = deliveryService.getOpenTickets();

        if (open.isEmpty()) {
            return event.reply()
                    .withEphemeral(true)
                    .withContent("📭 Активных тикетов нет (но ты все равно привези что-нибудь)");
        }

        StringBuilder sb = new StringBuilder();
        for (DeliveryTicket t : open) {
            sb.append(String.format("**#%d** %s %s → %s | %d%% выполнено\n",
                    t.getId(), t.getResourceEmoji(), t.getResourceName(),
                    t.getLocation(), t.getProgressPercent()));
        }

        return event.reply()
                .withEphemeral(true)
                .withEmbeds(discord4j.core.spec.EmbedCreateSpec.builder()
                        .color(Color.of(0x3498DB))
                        .title("📋 Активные тикеты доставки")
                        .description(sb.toString())
                        .build());
    }

    /**
     * Обновить embed в Discord после изменения тикета
     */
    public Mono<Void> refreshTicketMessage(DeliveryTicket ticket, GatewayDiscordClient client) {
        if (ticket.getDiscordMessageId() == null || ticket.getDiscordChannelId() == null) {
            log.warn("Ticket #{} has no attached message, skipping refresh", ticket.getId());
            return Mono.empty();
        }

        // Перечитываем тикет из БД чтобы получить актуальные данные
        DeliveryTicket fresh = deliveryService.getTicketById(ticket.getId())
                .orElse(ticket);

        log.info("=== REFRESH TICKET #{} ===", fresh.getId());
        log.info("messageId: {}", fresh.getDiscordMessageId());
        log.info("channelId: {}", fresh.getDiscordChannelId());
        log.info("delivered: {}/{}", fresh.getDeliveredAmount(), fresh.getTargetAmount());

        if (fresh.getDiscordMessageId() == null || fresh.getDiscordChannelId() == null) {
            log.error("MESSAGE ID OR CHANNEL ID IS NULL — attachMessage не сработал!");
            return Mono.empty();
        }

        List<Object[]> top = deliveryService.getTopContributors(fresh.getId());
        EmbedCreateSpec embed = embedBuilder.buildTicketEmbed(fresh, top);
        List<LayoutComponent> buttons = embedBuilder.buildTicketButtons(fresh);

        return client.getMessageById(
                        Snowflake.of(fresh.getDiscordChannelId()),
                        Snowflake.of(fresh.getDiscordMessageId())
                )
                .flatMap(message -> message.edit()
                        .withEmbeds(embed)
                        .withComponents(buttons)
                )
                .doOnSuccess(m -> log.info("Ticket #{} embed updated", fresh.getId()))
                .doOnError(e -> log.error("Failed to update ticket #{} embed: {}", fresh.getId(), e.getMessage()))
                //.onErrorResume(e -> Mono.empty()) // не крашим бота если обновление не удалось
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
