package com.jobradar.mail;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Reads the mailbox over IMAP with an app password.
 *
 * <p>This is the cheaper route in every sense: no Google Cloud project, no OAuth consent
 * screen, and no seven-day token expiry. The app password is a credential the user creates
 * and pastes into their own .env; Job Radar only reads it from there.
 */
@Component
public class ImapMailSource implements MailSource {

    private static final Logger log = LoggerFactory.getLogger(ImapMailSource.class);

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String folderName;

    public ImapMailSource(@Value("${jobradar.imap.host:imap.gmail.com}") String host,
                          @Value("${jobradar.imap.port:993}") int port,
                          @Value("${jobradar.imap.user:}") String user,
                          @Value("${jobradar.imap.password:}") String password,
                          @Value("${jobradar.imap.folder:INBOX}") String folderName) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.folderName = folderName;
    }

    @Override
    public String name() {
        return "IMAP";
    }

    @Override
    public boolean isConfigured() {
        return !user.isBlank() && !password.isBlank();
    }

    @Override
    public boolean isConnected() {
        return isConfigured();
    }

    @Override
    public List<GmailClient.Message> fetchRecent(Instant since, int max) {
        if (!isConfigured()) throw new IllegalStateException("IMAP is not configured");

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "15000");
        props.put("mail.imaps.timeout", "30000");

        List<GmailClient.Message> out = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            store = Session.getInstance(props).getStore("imaps");
            store.connect(host, port, user, password);
            folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);

            Message[] found = folder.search(
                    new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));

            // Newest first, and only as many as asked for.
            for (int i = found.length - 1; i >= 0 && out.size() < max; i--) {
                try {
                    out.add(convert(found[i]));
                } catch (MessagingException | java.io.IOException e) {
                    log.warn("skipping unreadable message: {}", e.toString());
                }
            }
            return out;
        } catch (AuthenticationFailedException e) {
            throw new IllegalStateException(
                    "The mail server rejected the login. With Gmail this means an app password "
                  + "is needed - your normal password will not work.", e);
        } catch (MessagingException e) {
            throw new IllegalStateException("IMAP failed: " + e.getMessage(), e);
        } finally {
            close(folder, store);
        }
    }

    private GmailClient.Message convert(Message m) throws MessagingException, java.io.IOException {
        String from = "";
        Address[] senders = m.getFrom();
        if (senders != null && senders.length > 0) {
            from = senders[0] instanceof InternetAddress ia
                    ? (ia.getAddress() == null ? ia.toString() : ia.getAddress())
                    : senders[0].toString();
        }
        String subject = m.getSubject() == null ? "" : m.getSubject();
        String body = textOf(m);
        Date received = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        String snippet = body == null ? "" : body.substring(0, Math.min(300, body.length()));

        return new GmailClient.Message(
                messageId(m), from, subject, snippet,
                received == null ? null : received.toInstant(), body);
    }

    private static String messageId(Message m) throws MessagingException {
        String[] header = m.getHeader("Message-ID");
        if (header != null && header.length > 0 && header[0] != null) return header[0];
        // No Message-ID is rare but legal; fall back to something stable for this mailbox.
        return "imap-" + m.getFolder().getFullName() + "-" + m.getMessageNumber();
    }

    /** First readable text part of the message; HTML is reduced to plain text. */
    private String textOf(Part part) throws MessagingException, java.io.IOException {
        if (part.isMimeType("text/plain")) {
            return String.valueOf(part.getContent());
        }
        if (part.isMimeType("text/html")) {
            return org.jsoup.Jsoup.parse(String.valueOf(part.getContent())).text();
        }
        if (part.getContent() instanceof Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = textOf(multipart.getBodyPart(i));
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                    if (sb.length() > 20000) break;
                }
            }
            return sb.toString();
        }
        return null;
    }

    private static void close(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) folder.close(false);
        } catch (MessagingException ignored) {
            // closing a read-only folder cannot lose anything
        }
        try {
            if (store != null && store.isConnected()) store.close();
        } catch (MessagingException ignored) {
            // same
        }
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", "IMAP");
        out.put("configured", isConfigured());
        out.put("connected", isConnected());
        if (isConfigured()) out.put("email", user);
        return out;
    }
}
