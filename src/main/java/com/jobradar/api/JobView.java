package com.jobradar.api;

import java.time.Instant;
import java.util.List;

/** What the UI shows for one job. */
public record JobView(
        long id,
        String company,
        String title,
        String location,
        String applyUrl,
        Instant postedAt,
        Instant firstSeenAt,
        /** true when postedAt is a real posting date, false when we fell back to first seen. */
        boolean dateIsReported,
        String state,
        boolean reposted,
        boolean cvGenerated,
        String applicationStatus,
        Integer fitScore,
        Integer chanceScore,
        String reason,
        Integer yearsRequired,
        String germanLevel,
        String matchState,
        List<String> sources
) {}
