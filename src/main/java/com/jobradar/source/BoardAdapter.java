package com.jobradar.source;

import java.util.List;

/**
 * A source that is searched by keyword rather than owned by one company - the public job
 * boards. Unlike an ATS adapter, one call returns jobs from many different employers, so
 * the company has to be resolved per job rather than known up front.
 */
public interface BoardAdapter {

    SourceType type();

    /** @param queries the active INCLUDE search terms */
    FetchResult search(List<String> queries);

    /**
     * The full text for one posting, for boards whose result list carries none. Called only
     * for jobs whose title already passed the rules, because each one is a second request.
     */
    default String fetchDescription(RawJob job) {
        return null;
    }

    /** How many {@link #fetchDescription} calls one scan may spend on this board. */
    default int maxDetailFetches() {
        return 0;
    }
}
