package com.jobradar.mail;

import java.time.Instant;
import java.util.List;

/**
 * Where replies are read from. Two implementations, because the obvious one is not the
 * better one: the Gmail API needs a Google Cloud project and, for a personal app that stays
 * unverified, a fresh consent every seven days; IMAP with an app password needs neither.
 */
public interface MailSource {

    String name();

    /** Whether the credentials exist at all. */
    boolean isConfigured();

    /** Whether it can actually be used right now. */
    boolean isConnected();

    /**
     * Recent messages, newest first. One fetch per sync rather than one search per
     * application - a few hundred messages are cheaper to scan locally than a query each.
     */
    List<GmailClient.Message> fetchRecent(Instant since, int max);

    java.util.Map<String, Object> status();
}
