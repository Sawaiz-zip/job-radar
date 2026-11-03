package com.jobradar.api;

import java.util.List;

/** Test-only bridge to the package-private query building in {@link JobRepository}. */
public final class JobRepositoryAccess {

    private JobRepositoryAccess() {}

    public static String where(JobFilter f, List<Object> params) {
        return JobRepository.where(f, params);
    }

    public static List<String> words(String text) {
        return JobRepository.words(text);
    }

    public static List<String> germanAbove(String level) {
        return JobFilter.germanAbove(level);
    }

    public static List<String> cityVariants(String city) {
        return JobFilter.cityVariants(city);
    }
}
