package com.example.bot2;

import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiscordConfig {

    @Value("${discord.token}")
    private String token;

    @Bean
    public GatewayDiscordClient discordClient() {
        return DiscordClientBuilder.create(token)
                .build()
                .gateway()
                .setEnabledIntents(IntentSet.of(
                        Intent.GUILDS, Intent.GUILD_MESSAGES, Intent.GUILD_MEMBERS))
                .login()
                .block();
    }
}
