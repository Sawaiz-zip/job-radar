package com.jobradar.source;

import java.util.List;

/**
 * The outcome of one adapter call. An adapter never throws at the caller:
 * a failure is data (constitution #3, "fail loudly, never silently"), so that
 * the health monitor can tell a broken source from an empty one.
 */
public record FetchResult(List<RawJob> jobs, Integer httpStatus, String error) {

    public static FetchResult ok(List<RawJob> jobs, int status) {
        return new FetchResult(jobs, status, null);
    }

    public static FetchResult failed(String error, Integer status) {
        return new FetchResult(List.of(), status, error);
    }

    public boolean isError() {
        return error != null;
    }
}
