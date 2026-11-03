package com.jobradar.mail;

import com.jobradar.dedupe.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reads the mailbox for replies to applications and records where each one stands.
 *
 * <p>Classification is keyword-first and deliberately cautious. A wrong "rejected" is worse
 * than no answer at all, so anything the keywords cannot settle is stored as UNCLEAR with
 * the subject line attached, and the UI says so rather than inventing a status.
 */
@Service
public class ApplicationMailSync {

    private static final Logger log = LoggerFactory.getLogger(ApplicationMailSync.class);

    private static final Pattern REJECTED = Pattern.compile(
            "\\b(unfortunately|regret to inform|not (?:be )?(?:moving|proceeding) forward|"
          + "decided to (?:move forward|proceed) with (?:other|another)|not selected|"
          + "we will not be|unsuccessful|"
          + "leider|absage|eine absage|nicht ber(?:ü|ue)cksichtigen|nicht weiter ber(?:ü|ue)cksichtigt|"
          + "f(?:ü|ue)r eine andere bewerberin|anderen kandidaten entschieden|"
          + "bewerbungsprozess nicht fortsetzen)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INTERVIEW = Pattern.compile(
            "\\b(invite you (?:to|for)|interview|schedule a (?:call|chat|conversation)|"
          + "next (?:step|stage|round)|speak with you|meet (?:with )?(?:you|our team)|"
          + "einladung|vorstellungsgespr(?:ä|ae)ch|kennenlernen|gespr(?:ä|ae)ch vereinbaren|"
          + "telefoninterview|n(?:ä|ae)chsten schritt)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OFFER = Pattern.compile(
            "\\b(job offer|offer of employment|pleased to offer|vertragsangebot|"
          + "wir freuen uns, ihnen anzubieten|angebot unterbreiten)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ACKNOWLEDGED = Pattern.compile(
            "\\b(received your application|thank you for (?:your )?(?:applying|application|interest)|"
          + "application (?:has been )?received|we have received|currently reviewing|"
          + "eingangsbest(?:ä|ae)tigung|bewerbung (?:ist )?(?:bei uns )?eingegangen|"
          + "vielen dank f(?:ü|ue)r (?:ihre|deine) bewerbung|wir haben ihre bewerbung erhalten)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ImapMailSource imap;
    private final GmailApiSource gmailApi;
    private final JdbcClient db;
    private final Normalizer normalizer;

    private final String preferredProvider;

    public ApplicationMailSync(ImapMailSource imap, GmailApiSource gmailApi,
                               JdbcClient db, Normalizer normalizer,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${jobradar.mail.provider:gmail}") String preferredProvider) {
        this.imap = imap;
        this.gmailApi = gmailApi;
        this.db = db;
        this.normalizer = normalizer;
        this.preferredProvider = preferredProvider;
    }

    public record SyncResult(boolean connected, int applicationsChecked, int messagesFound,
                             int statusesUpdated, int unclear, String error) {}

    public SyncResult sync(int lookbackDays) {
        MailSource source = activeSource();
        if (source == null) {
            return new SyncResult(false, 0, 0, 0, 0,
                    "No mailbox is set up. Either add IMAP_USER and IMAP_PASSWORD to .env "
                  + "(an app password, no Google Cloud project needed), or configure a Google "
                  + "OAuth client and connect Gmail.");
        }
        if (!source.isConnected()) {
            return new SyncResult(false, 0, 0, 0, 0,
                    source.name() + " is set up but not connected yet. Click Connect Gmail and "
                  + "grant read-only access.");
        }

        List<Map<String, Object>> applications = db.sql("""
                SELECT a.id, a.title, a.applied_at, c.id AS company_id, c.name AS company,
                       c.primary_domain, c.careers_url
                  FROM application a JOIN company c ON c.id = a.company_id
                 ORDER BY a.applied_at DESC
                """).query().listOfRows();
        if (applications.isEmpty()) {
            return new SyncResult(true, 0, 0, 0, 0, null);
        }

        Instant since = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
        List<GmailClient.Message> messages;
        try {
            // One fetch, then match locally: a few hundred messages are far cheaper to scan
            // here than one mailbox query per application.
            messages = source.fetchRecent(since, 400);
        } catch (RuntimeException e) {
            recordError(e.getMessage());
            return new SyncResult(true, applications.size(), 0, 0, 0, e.getMessage());
        }

        int matched = 0, updated = 0, unclear = 0;
        for (Map<String, Object> app : applications) {
            long applicationId = ((Number) app.get("id")).longValue();
            long companyId = ((Number) app.get("company_id")).longValue();
            String company = (String) app.get("company");
            String domain = domainOf((String) app.get("careers_url"));
            Instant appliedAt = app.get("applied_at") instanceof Timestamp ts
                    ? ts.toInstant().minus(2, ChronoUnit.DAYS)
                    : since;

            GmailClient.Message best = null;
            String bestVerdict = null;
            for (GmailClient.Message m : messages) {
                if (m.receivedAt() != null && m.receivedAt().isBefore(appliedAt)) continue;
                if (!mentionsEmployer(m, company, domain)) continue;
                matched++;
                String verdict = classify(m);
                // Later stages win: an invitation matters more than the acknowledgement
                // that arrived before it.
                if (best == null || rank(verdict) > rank(bestVerdict)) {
                    best = m;
                    bestVerdict = verdict;
                }
                remember(m, applicationId, companyId, verdict);
            }

            if (best != null) {
                if ("UNCLEAR".equals(bestVerdict)) unclear++;
                db.sql("""
                       UPDATE application SET response_status = ?, response_at = ?, response_subject = ?,
                                              response_snippet = ?, response_checked_at = now()
                        WHERE id = ?
                       """)
                  .params(bestVerdict,
                          best.receivedAt() == null ? null : Timestamp.from(best.receivedAt()),
                          trim(best.subject()), trim(best.snippet()), applicationId)
                  .update();
                updated++;
            } else {
                db.sql("UPDATE application SET response_checked_at = now() WHERE id = ?")
                  .param(applicationId).update();
            }
        }

        db.sql("""
               INSERT INTO gmail_account (id, last_sync_at, last_error) VALUES (1, now(), NULL)
               ON CONFLICT (id) DO UPDATE SET last_sync_at = now(), last_error = NULL
               """).update();
        log.info("{} sync: {} applications, {} messages fetched, {} matched, {} statuses updated",
                source.name(), applications.size(), messages.size(), matched, updated);
        return new SyncResult(true, applications.size(), matched, updated, unclear, null);
    }

    /**
     * Which mailbox route to use. Set by {@code jobradar.mail.provider}; whatever is
     * preferred wins when configured, and the other one is only a fallback.
     */
    private MailSource activeSource() {
        boolean preferImap = "imap".equalsIgnoreCase(preferredProvider);
        MailSource first = preferImap ? imap : gmailApi;
        MailSource second = preferImap ? gmailApi : imap;
        if (first.isConfigured()) return first;
        if (second.isConfigured()) return second;
        return null;
    }

    /**
     * Does this message plausibly come from that employer? Sender domain is the strong
     * signal; the name in the subject is the fallback, since ATSs mail on a company's behalf
     * from their own domain and put the employer in the subject line.
     */
    public boolean mentionsEmployer(GmailClient.Message m, String company, String domain) {
        String normalizedCompany = normalizer.normalizeCompany(company);
        if (normalizedCompany.isBlank()) return false;

        String from = m.from() == null ? "" : m.from().toLowerCase(Locale.ROOT);
        if (domain != null && from.contains(domain.toLowerCase(Locale.ROOT))) return true;

        // Compare on the normalized form so "Zalando SE" matches a subject saying "Zalando".
        String firstWord = normalizedCompany.split(" ")[0];
        if (firstWord.length() < 4) return false;

        String subject = normalizer.normalizeCompany(m.subject() == null ? "" : m.subject());
        if (subject.contains(normalizedCompany) || subject.contains(firstWord)) return true;
        return normalizer.normalizeCompany(from).contains(firstWord);
    }

    private static String domainOf(String url) {
        if (url == null || url.isBlank()) return null;
        String d = url.replaceFirst("^https?://", "").replaceFirst("^www\\.", "").split("/")[0];
        return d.contains(".") ? d : null;
    }

    public String classify(GmailClient.Message m) {
        String text = (m.subject() + "\n" + m.snippet() + "\n"
                    + (m.body() == null ? "" : m.body())).toLowerCase(Locale.ROOT);
        if (OFFER.matcher(text).find()) return "OFFER";
        if (REJECTED.matcher(text).find()) return "REJECTED";
        if (INTERVIEW.matcher(text).find()) return "INTERVIEW";
        if (ACKNOWLEDGED.matcher(text).find()) return "ACKNOWLEDGED";
        return "UNCLEAR";
    }

    private static int rank(String verdict) {
        if (verdict == null) return -1;
        return switch (verdict) {
            case "OFFER" -> 4;
            case "INTERVIEW" -> 3;
            case "REJECTED" -> 2;
            case "ACKNOWLEDGED" -> 1;
            default -> 0;
        };
    }

    private void remember(GmailClient.Message m, long applicationId, long companyId, String verdict) {
        db.sql("""
               INSERT INTO email_message (message_id, application_id, company_id, from_address, subject,
                                          received_at, verdict)
               VALUES (?,?,?,?,?,?,?)
               ON CONFLICT (message_id) DO UPDATE SET verdict = EXCLUDED.verdict
               """)
          .params(m.id(), applicationId, companyId, trim(m.from()), trim(m.subject()),
                  m.receivedAt() == null ? null : Timestamp.from(m.receivedAt()), verdict)
          .update();
    }

    private void recordError(String message) {
        db.sql("UPDATE gmail_account SET last_error = ?, last_sync_at = now() WHERE id = 1")
          .param(trim(message)).update();
    }

    private static String trim(String s) {
        if (s == null) return null;
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        MailSource active = activeSource();
        out.put("provider", active == null ? "none" : active.name());
        out.put("credentialsConfigured", active != null);
        out.put("connected", active != null && active.isConnected());
        out.put("imapConfigured", imap.isConfigured());
        out.put("oauthConfigured", gmailApi.isConfigured());
        if (imap.isConfigured()) out.put("email", imap.status().get("email"));
        List<Map<String, Object>> rows = db.sql(
                "SELECT email, connected_at, last_sync_at, last_error FROM gmail_account WHERE id = 1")
                .query().listOfRows();
        if (!rows.isEmpty()) {
            rows.get(0).forEach((k, v) -> { if (v != null) out.put(k, v); });
        }
        return out;
    }
}
