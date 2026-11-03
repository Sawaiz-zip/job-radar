package com.jobradar.company;

import com.jobradar.source.SourceType;

public record Company(
        Long id,
        String name,
        String normalizedName,
        String primaryDomain,
        String careersUrl,
        SourceType atsType,
        String atsTenant,
        String atsSite,
        String atsRegion,
        String category,
        String cities,
        String language,
        String priority,
        String status,
        boolean enabled,
        String origin,
        String notes
) {}
