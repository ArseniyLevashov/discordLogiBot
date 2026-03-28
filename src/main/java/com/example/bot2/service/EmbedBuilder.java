package com.example.bot2.service;

import com.example.bot2.entity.DeliveryTicket;
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
    private static final int BAR_LENGTH = 20; // символов в прогресс-баре

    /**
     * Главный embed тикета с прогресс-баром
     */
    public EmbedCreateSpec buildTicketEmbed(DeliveryTicket ticket,
                                            List<Object[]> topContributors) {
        int percent = ticket.getProgressPercent();
        String progressBar = buildProgressBar(percent);
        String statusLine = buildStatusLine(ticket);

        // Топ-5 доставщиков
        StringBuilder contributors = new StringBuilder();
        if (topContributors.isEmpty()) {
            contributors.append("*Никто ещё не доставил*");
        } else {
            String[] medals = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣"};
            for (int i = 0; i < Math.min(5, topContributors.size()); i++) {
                String name = (String) topContributors.get(i)[1];
                long total = ((Number) topContributors.get(i)[2]).longValue();
                contributors.append(String.format("%s **%s** — %,d %s\n",
                        medals[i], name, total, ticket.getResourceEmoji()));
            }
        }

        // Цвет в зависимости от статуса
        Color color = switch (ticket.getStatus()) {
            case OPEN -> percent < 50
                    ? Color.of(0xE74C3C)   // красный — мало
                    : Color.of(0xF39C12);  // оранжевый — средне
            case COMPLETED -> Color.of(0x2ECC71); // зелёный — готово
            case CANCELLED -> Color.of(0x95A5A6); // серый — отменено
        };

        return EmbedCreateSpec.builder()
                .color(color)
                .title(statusLine)
                // ← убери addField с пустым value, перенеси в description
                .description(String.format("%s Ресурс: **%s**  |  📍 Место: **%s**",
                        ticket.getResourceEmoji(),
                        ticket.getResourceName(),
                        ticket.getLocation()))
                .addField("📊 Прогресс доставки",
                        String.format("%s\n**%,d / %,d** %s  (%d%%)",
                                progressBar,
                                ticket.getDeliveredAmount(),
                                ticket.getTargetAmount(),
                                ticket.getResourceEmoji(),
                                percent),
                        false)
                .addField("🏆 Топ доставщиков",
                        contributors.toString().isEmpty() ? "*Никто ещё не доставил*" : contributors.toString(),
                        false)
                .footer(String.format("Тикет #%d | Создал: %s",
                        ticket.getId(), ticket.getCreatedByName()), "")
                .build();
    }

    /**
     * Прогресс-бар: ████████████░░░░░░░░ 60%
     */
    private String buildProgressBar(int percent) {
        int filled = (int) Math.round(BAR_LENGTH * percent / 100.0);
        int empty = BAR_LENGTH - filled;

        String filledBar = "█".repeat(filled);
        String emptyBar = "░".repeat(empty);

        String color;
        if (percent >= 100) color = "🟩";
        else if (percent >= 75) color = "🟨";
        else if (percent >= 40) color = "🟧";
        else color = "🟥";

        return String.format("%s `%s%s` **%d%%**",
                color, filledBar, emptyBar, percent);
    }

    private String buildStatusLine(DeliveryTicket ticket) {
        return switch (ticket.getStatus()) {
            case OPEN -> "📦 Активный тикет доставки";
            case COMPLETED -> "✅ Доставка завершена!";
            case CANCELLED -> "❌ Тикет отменён";
        };
    }

    /**
     * Кнопки под тикетом
     */
    public List<LayoutComponent> buildTicketButtons(DeliveryTicket ticket) {
        if (ticket.getStatus() != DeliveryTicket.TicketStatus.OPEN) {
            return List.of(); // кнопок нет для закрытых тикетов
        }

        Button deliverButton = Button.primary(
                "deliver:" + ticket.getId(),
                ReactionEmoji.unicode("📦"),
                "Я доставил!"
        );

        Button statusButton = Button.secondary(
                "status:" + ticket.getId(),
                ReactionEmoji.unicode("📊"),
                "Мой вклад"
        );

        return List.of(ActionRow.of(deliverButton, statusButton));
    }

    /**
     * Embed для эфемерного ответа после вклада
     */
    public EmbedCreateSpec buildContributionConfirmEmbed(
            DeliveryService.ContributeResult result, long amount) {
        DeliveryTicket ticket = result.getTicket();

        if (!result.isSuccess()) {
            return discord4j.core.spec.EmbedCreateSpec.builder()
                    .color(Color.RED)
                    .title("❌ Ошибка (иди жаловаться дексу)")
                    .description(result.getMessage())
                    .build();
        }

        String description = String.format(
                "Ты молодец, ты доставил **%,d %s %s**!\nОбщий прогресс: **%d%%**",
                amount,
                ticket.getResourceName(),
                ticket.getResourceEmoji(),
                ticket.getProgressPercent()
        );

        if ("COMPLETED".equals(result.getMessage())) {
            description += "\n\n🎉 **Тикет выполнен! Цель достигнута!**";
        }

        return discord4j.core.spec.EmbedCreateSpec.builder()
                .color(Color.of(0x2ECC71))
                .title("✅ Доставка записана!")
                .description(description)
                .build();
    }
}
