package com.jobradar;

import com.jobradar.dedupe.Normalizer;
import com.jobradar.mail.ApplicationMailSync;
import com.jobradar.mail.GmailClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wrongly calling something a rejection is worse than saying nothing, so the classifier
 * has to be right on the common phrasings and honest ("UNCLEAR") everywhere else.
 */
class MailClassifierTest {

    private final ApplicationMailSync sync =
            new ApplicationMailSync(null, null, null, new Normalizer(), "gmail");

    private String verdict(String subject, String body) {
        return sync.classify(new GmailClient.Message("1", "hr@example.com", subject, "", Instant.now(), body));
    }

    @Test
    void englishRejectionsAreRecognised() {
        assertThat(verdict("Your application", "Unfortunately we will not be moving forward")).isEqualTo("REJECTED");
        assertThat(verdict("Update", "we have decided to proceed with other candidates")).isEqualTo("REJECTED");
        assertThat(verdict("Application update", "We regret to inform you")).isEqualTo("REJECTED");
    }

    @Test
    void germanRejectionsAreRecognised() {
        assertThat(verdict("Ihre Bewerbung", "leider müssen wir Ihnen eine Absage erteilen")).isEqualTo("REJECTED");
        assertThat(verdict("Bewerbung", "können wir Sie nicht berücksichtigen")).isEqualTo("REJECTED");
    }

    @Test
    void invitationsOutrankEverythingButAnOffer() {
        assertThat(verdict("Next steps", "We would like to invite you for an interview")).isEqualTo("INTERVIEW");
        assertThat(verdict("Einladung", "Wir laden Sie zum Vorstellungsgespräch ein")).isEqualTo("INTERVIEW");
        assertThat(verdict("Congratulations", "We are pleased to offer you the position")).isEqualTo("OFFER");
    }

    @Test
    void autoAcknowledgementsAreNotMistakenForAnAnswer() {
        assertThat(verdict("Thanks", "We have received your application and are currently reviewing it"))
                .isEqualTo("ACKNOWLEDGED");
        assertThat(verdict("Eingangsbestätigung", "Vielen Dank für Ihre Bewerbung")).isEqualTo("ACKNOWLEDGED");
    }

    private boolean matches(String from, String subject, String company, String domain) {
        return sync.mentionsEmployer(
                new GmailClient.Message("1", from, subject, "", Instant.now(), ""), company, domain);
    }

    @Test
    void repliesAreTiedToTheRightEmployer() {
        // Sender domain is the strong signal.
        assertThat(matches("careers@acme.example", "Your application", "Acme", "acme.example")).isTrue();
        // ATSs mail on a company's behalf and name the employer in the subject.
        assertThat(matches("no-reply@greenhouse.io", "Your application to Acme", "Acme", "acme.example"))
                .isTrue();
        // Legal-form differences must not break the match.
        assertThat(matches("hr@example.com", "Zalando application update", "Zalando SE", null)).isTrue();
    }

    @Test
    void unrelatedMailIsNotAttachedToAnApplication() {
        assertThat(matches("newsletter@spotify.com", "Your weekly mix", "Acme", "acme.example")).isFalse();
        assertThat(matches("hr@othercorp.com", "An update on your application", "Acme", "acme.example"))
                .isFalse();
    }

    @Test
    void veryShortCompanyNamesDoNotMatchOnTheNameAlone() {
        // "n8n" or "A11" would otherwise match almost any subject line containing them.
        assertThat(matches("hello@random.com", "n8n workflow tips", "n8n", null)).isFalse();
    }

    @Test
    void anythingElseStaysUnclearRatherThanGuessing() {
        assertThat(verdict("Quick question", "Could you send your certificates?")).isEqualTo("UNCLEAR");
        assertThat(verdict("Newsletter", "Read our engineering blog")).isEqualTo("UNCLEAR");
    }
}
