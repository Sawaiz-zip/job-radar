package com.jobradar.source;

import java.time.Instant;

/** One posting exactly as a source reported it, before any dedupe or scoring. */
public record RawJob(
        SourceType sourceType,
        String tenant,
        String externalId,
        String title,
        String location,
        String descriptionText,
        String url,
        String companyName,
        Instant postedAt,
        String rawPayload
) {}
