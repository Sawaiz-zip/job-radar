package com.jobradar.mail;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The OAuth route, wrapping {@link GmailClient} behind the common interface. */
@Component
public class GmailApiSource implements MailSource {

    private final GmailClient gmail;

    public GmailApiSource(GmailClient gmail) {
        this.gmail = gmail;
    }

    @Override
    public String name() {
        return "Gmail API";
    }

    @Override
    public boolean isConfigured() {
        return gmail.hasCredentials();
    }

    @Override
    public boolean isConnected() {
        return gmail.isConnected();
    }

    @Override
    public List<GmailClient.Message> fetchRecent(Instant since, int max) {
        String query = "after:" + since.getEpochSecond() + " -in:chats -category:promotions";
        return gmail.search(gmail.accessToken(), query, max);
    }

    @Override
    public Map<String, Object> status() {
        return Map.of("provider", "Gmail API",
                      "configured", isConfigured(),
                      "connected", isConnected());
    }
}
