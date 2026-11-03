package com.jobradar.source;

import com.jobradar.company.Company;

/**
 * One source behind one interface (constitution #5): adding a source must not
 * change the scan pipeline.
 */
public interface JobSourceAdapter {

    SourceType type();

    /** True when this adapter can fetch for the given company. */
    boolean supports(Company company);

    /** Every currently open posting for the company. */
    FetchResult fetch(Company company);
}
