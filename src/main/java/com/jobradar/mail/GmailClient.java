package com.jobradar.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Gmail access over the plain REST API - no SDK, since all this needs is a refresh-token
 * exchange and one search endpoint.
 *
 * <p>Read-only scope. The user performs the consent themselves in their browser; Job Radar
 * only ever holds the refresh token they granted, and can be disconnected by deleting one
 * database row.
 */
@Component
public class GmailClient {

    private static final Logger log = LoggerFactory.getLogger(GmailClient.class);
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String API = "https://gmail.googleapis.com/gmail/v1/users/me";
    public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

    private final RestClient http = RestClient.create();
    private final ObjectMapper json = new ObjectMapper();
    private final JdbcClient db;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GmailClient(JdbcClient db,
                       @Value("${jobradar.gmail.client-id:}") String clientId,
                       @Value("${jobradar.gmail.client-secret:}") String clientSecret,
                       @Value("${jobradar.gmail.redirect-uri:http://localhost:8090/api/v1/gmail/callback}")
                       String redirectUri) {
        this.db = db;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    public boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public Optional<String> refreshToken() {
        return db.sql("SELECT refresh_token FROM gmail_account WHERE id = 1")
                 .query(String.class).optional().filter(t -> t != null && !t.isBlank());
    }

    public boolean isConnected() {
        return hasCredentials() && refreshToken().isPresent();
    }

    public String authUrl() {
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPE)
                // offline + consent is what actually returns a refresh token.
                + "&access_type=offline&prompt=consent";
    }

    /** Exchanges the one-time code for a refresh token and stores it. */
    public void completeAuth(String code) {
        JsonNode token = post(TOKEN_URL, Map.of(
                "code", code,
                "client_id", clientId,
                "client_secret", clientSecret,
                "redirect_uri", redirectUri,
                "grant_type", "authorization_code"));

        String refresh = token.path("refresh_token").asText(null);
        if (refresh == null) {
            throw new IllegalStateException(
                    "Google did not return a refresh token. Remove Job Radar from your Google "
                  + "account's third-party access and connect again.");
        }
        String access = token.path("access_token").asText(null);
        String email = access == null ? null : profileEmail(access);

        db.sql("""
               INSERT INTO gmail_account (id, email, refresh_token, connected_at, last_error)
               VALUES (1, ?, ?, now(), NULL)
               ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email,
                   refresh_token = EXCLUDED.refresh_token, connected_at = now(), last_error = NULL
               """).params(email, refresh).update();
        log.info("Gmail connected for {}", email);
    }

    public void disconnect() {
        db.sql("DELETE FROM gmail_account WHERE id = 1").update();
    }

    /**
     * Swaps the stored refresh token for a short-lived access token.
     *
     * <p>Google expires the refresh token of an app that is still in "Testing" after seven
     * days, and the failure looks like an ordinary rejection. It is caught here and turned
     * into a message that says what to actually do, rather than surfacing "invalid_grant".
     */
    public String accessToken() {
        String refresh = refreshToken().orElseThrow(() -> new IllegalStateException("Gmail is not connected"));
        JsonNode token;
        try {
            token = post(TOKEN_URL, Map.of(
                    "refresh_token", refresh,
                    "client_id", clientId,
                    "client_secret", clientSecret,
                    "grant_type", "refresh_token"));
        } catch (IllegalStateException e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            if (detail.contains("invalid_grant") || detail.contains("expired") || detail.contains("revoked")) {
                markDisconnected();
                throw new IllegalStateException(
                        "Google has expired this authorisation. Apps that are still in Testing "
                      + "lose access after 7 days - click Connect Gmail again to renew it.", e);
            }
            throw e;
        }
        return requireAccessToken(token);
    }

    private void markDisconnected() {
        db.sql("UPDATE gmail_account SET last_error = ? WHERE id = 1")
          .param("authorisation expired - reconnect").update();
    }

    private static String requireAccessToken(JsonNode token) {
        String access = token.path("access_token").asText(null);
        if (access == null) {
            throw new IllegalStateException("Gmail refused the stored token - reconnect the account");
        }
        return access;
    }

    public record Message(String id, String from, String subject, String snippet, Instant receivedAt, String body) {}

    /** @param query a Gmail search query, the same syntax as the search box */
    public List<Message> search(String accessToken, String query, int max) {
        JsonNode list = get(API + "/messages?maxResults=" + max + "&q=" + enc(query), accessToken);
        List<Message> out = new ArrayList<>();
        for (JsonNode m : list.path("messages")) {
            String id = m.path("id").asText(null);
            if (id == null) continue;
            try {
                out.add(message(accessToken, id));
            } catch (RuntimeException e) {
                log.warn("could not read message {}: {}", id, e.toString());
            }
        }
        return out;
    }

    private Message message(String accessToken, String id) {
        JsonNode m = get(API + "/messages/" + id + "?format=full", accessToken);
        Map<String, String> headers = new HashMap<>();
        for (JsonNode h : m.path("payload").path("headers")) {
            headers.put(h.path("name").asText("").toLowerCase(Locale.ROOT), h.path("value").asText(""));
        }
        long internal = m.path("internalDate").asLong(0);
        return new Message(id,
                headers.getOrDefault("from", ""),
                headers.getOrDefault("subject", ""),
                m.path("snippet").asText(""),
                internal > 0 ? Instant.ofEpochMilli(internal) : null,
                bodyText(m.path("payload")));
    }

    /** Walks the MIME tree for the first text part; HTML is stripped to readable text. */
    private String bodyText(JsonNode payload) {
        String mime = payload.path("mimeType").asText("");
        String data = payload.path("body").path("data").asText(null);
        if (data != null && (mime.startsWith("text/plain") || mime.startsWith("text/html"))) {
            String decoded = new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
            return mime.startsWith("text/html") ? org.jsoup.Jsoup.parse(decoded).text() : decoded;
        }
        for (JsonNode part : payload.path("parts")) {
            String text = bodyText(part);
            if (text != null && !text.isBlank()) return text;
        }
        return null;
    }

    private String profileEmail(String accessToken) {
        try {
            return get(API + "/profile", accessToken).path("emailAddress").asText(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode get(String url, String accessToken) {
        try {
            String raw = http.get().uri(url).header("Authorization", "Bearer " + accessToken)
                    .retrieve().body(String.class);
            return json.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            throw new IllegalStateException("Gmail request failed: " + e.getMessage(), e);
        }
    }

    private JsonNode post(String url, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (!body.isEmpty()) body.append('&');
            body.append(k).append('=').append(enc(v));
        });
        try {
            String raw = http.post().uri(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(body.toString())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> { })
                    .body(String.class);
            JsonNode node = json.readTree(raw == null ? "{}" : raw);
            if (node.has("error")) {
                throw new IllegalStateException("Google rejected the request: "
                        + node.path("error_description").asText(node.path("error").asText()));
            }
            return node;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("token request failed: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return UriUtils.encode(s, StandardCharsets.UTF_8);
    }
}
