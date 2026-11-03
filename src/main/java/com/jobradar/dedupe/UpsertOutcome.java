package com.jobradar.dedupe;

/** How an incoming posting resolved against what is already stored. */
public enum UpsertOutcome {
    /** No match on any layer: a genuinely new job row was created. */
    CREATED,
    /** Matched on canonical URL (L1). */
    MATCHED_CANONICAL_URL,
    /** Matched on (source_type, tenant, external_id) (L2). */
    MATCHED_SOURCE_ID,
    /** Matched on (company, normalized title, city) (L3) - a second source for one job. */
    MATCHED_FINGERPRINT,
    /** Was CLOSED, came back inside the repost window: same row reopened. */
    REOPENED
}
