package com.salescode.cofman.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SlackConfig {

    @Value("${slack.bot.token}")
    private String botToken;

    @Value("${slack.signing.secret}")
    private String signingSecret;

    @Bean
    public Slack slack() {
        return Slack.getInstance();
    }

    @Bean
    public MethodsClient methodsClient(Slack slack) {
        return slack.methods(botToken);
    }
}
