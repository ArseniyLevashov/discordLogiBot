package com.example.bot2;

import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
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
                .login()
                .block();
    }
}
