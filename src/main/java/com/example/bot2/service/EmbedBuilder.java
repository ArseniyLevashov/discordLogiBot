package com.example.bot2.service;

import com.example.bot2.entity.DeliveryTicket;
import com.example.bot2.entity.TicketResource;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.component.LayoutComponent;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.rest.util.Color;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EmbedBuilder {

    private static final int BAR_LENGTH = 18;

    public EmbedCreateSpec buildTicketEmbed(DeliveryTicket ticket) {
        Color color = switch (ticket.getStatus()) {
            case OPEN      -> Color.of(0xF39C12);
            case COMPLETED -> Color.of(0x2ECC71);
            case CANCELLED -> Color.of(0x95A5A6);
        };

        discord4j.core.spec.EmbedCreateSpec.Builder builder =
                discord4j.core.spec.EmbedCreateSpec.builder()
                        .color(color)
                        .title(buildStatusLine(ticket))
                        .description("📍 Место доставки: **" + ticket.getLocation() + "**");

        // Прогресс-бар для каждого ресурса
        for (TicketResource resource : ticket.getResources()) {
            int percent = resource.getProgressPercent();
            String bar = buildProgressBar(percent);

            builder.addField(
                    resource.getResourceName(),  // просто название без эмодзи
                    String.format("%s\n**%,d / %,d** (%d%%)",
                            buildProgressBar(resource.getProgressPercent()),
                            resource.getDeliveredAmount(),
                            resource.getTargetAmount(),
                            resource.getProgressPercent()),
                    false
            );
        }

        builder.footer(String.format("Тикет #%d | Создал: %s",
                ticket.getId(), ticket.getCreatedByName()), "");

        return builder.build();
    }

    private String buildProgressBar(int percent) {
        int filled = (int) Math.round(BAR_LENGTH * percent / 100.0);
        int empty  = BAR_LENGTH - filled;
        String color = percent >= 100 ? "🟩" : percent >= 75 ? "🟨" : percent >= 40 ? "🟧" : "🟥";
        return String.format("%s `%s%s` **%d%%**",
                color, "█".repeat(filled), "░".repeat(empty), percent);
    }

    private String buildStatusLine(DeliveryTicket ticket) {
        return switch (ticket.getStatus()) {
            case OPEN      -> "📦 Активный тикет доставки";
            case COMPLETED -> "✅ Доставка завершена!";
            case CANCELLED -> "❌ Тикет отменён";
        };
    }

    public List<LayoutComponent> buildTicketButtons(DeliveryTicket ticket) {
        if (ticket.getStatus() != DeliveryTicket.TicketStatus.OPEN) return List.of();

        Button deliverButton = Button.primary(
                "deliver:" + ticket.getId(),
                ReactionEmoji.unicode("📦"),
                "Я доставил!"
        );
        return List.of(ActionRow.of(deliverButton));
    }

    public EmbedCreateSpec buildContributionConfirmEmbed(
            DeliveryService.ContributeResult result, String resourceName, long amount) {

        String description = String.format("Ты доставил **%,d** единиц **%s**!", amount, resourceName);

        if ("COMPLETED".equals(result.getMessage())) {
            description += "\n\n🎉 **Все ресурсы доставлены! Тикет закрыт!**";
        }

        return discord4j.core.spec.EmbedCreateSpec.builder()
                .color(Color.of(0x2ECC71))
                .title("✅ Доставка записана!")
                .description(description).build();
    }
}
