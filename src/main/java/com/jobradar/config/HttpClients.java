package com.jobradar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class HttpClients {

    @Bean
    RestClient restClient(RadarProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                // Constitution #4: identify the tool honestly on every request.
                .defaultHeader("User-Agent", props.userAgent())
                .defaultHeader("Accept-Language", "en,de;q=0.8")
                .build();
    }

    @ConfigurationProperties(prefix = "jobradar")
    public record RadarProperties(
            String userAgent,
            String contactEmail,
            double perHostRps,
            int closeAfterMissingRuns,
            int repostWindowDays
    ) {
        public RadarProperties {
            if (userAgent == null || userAgent.isBlank()) {
                // Identify the tool honestly, and give site operators a way to reach the person
                // running it, which is what the robots/etiquette guidance of most boards asks for.
                userAgent = (contactEmail == null || contactEmail.isBlank())
                        ? "JobRadar/0.1 (self-hosted job search)"
                        : "JobRadar/0.1 (self-hosted job search; " + contactEmail.trim() + ")";
            }
            if (perHostRps <= 0) perHostRps = 1.0;
            if (closeAfterMissingRuns <= 0) closeAfterMissingRuns = 2;
            if (repostWindowDays <= 0) repostWindowDays = 90;
        }
    }
}
