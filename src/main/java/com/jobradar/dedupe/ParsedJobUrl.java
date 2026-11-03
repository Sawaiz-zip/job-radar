package com.jobradar.dedupe;

import com.jobradar.source.SourceType;

/**
 * The result of canonicalizing a job URL.
 *
 * <p>{@code canonicalUrl} is the L1 dedupe key and is unique in {@code job_source}.
 * For a recognised ATS it is a compact key such as {@code greenhouse:parloa:4656966101}
 * so that every URL variant of the same posting collapses to one string; for anything
 * else it is the scrubbed URL itself.
 */
public record ParsedJobUrl(
        SourceType type,
        String tenant,
        String site,
        String externalId,
        String canonicalUrl,
        String originalUrl
) {
    public boolean isKnownAts() {
        return switch (type) {
            case GREENHOUSE, LEVER, ASHBY, SMARTRECRUITERS, RECRUITEE, PERSONIO, WORKDAY,
                 SUCCESSFACTORS -> true;
            default -> false;
        };
    }
}
