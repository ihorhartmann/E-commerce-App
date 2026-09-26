package com.hartmann.infrastructure.config;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class StripeConfig {
    @Bean
    public StripeClient stripeClient(
            @Value("${stripe.secret-key}") String secretKey,
            @Value("${stripe.connect-timeout}") Duration connectTimeout,
            @Value("${stripe.read-timeout}") Duration readTimeout
    ) {
        return StripeClient.builder()
                .setApiKey(secretKey)
                .setConnectTimeout((int) connectTimeout.toMillis())
                .setReadTimeout((int) readTimeout.toMillis())
                .setMaxNetworkRetries(0)
                .build();
    }
}
