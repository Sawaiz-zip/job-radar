-- Applications become their own records rather than flags on a job row.
-- History backfilled from the CV folders has no job posting behind it - those postings are
-- long closed and were never scraped - so an application must be able to exist without one.
CREATE TABLE application (
    id                  BIGSERIAL PRIMARY KEY,
    company_id          BIGINT      NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    job_id              BIGINT      REFERENCES job(id) ON DELETE SET NULL,
    title               TEXT,                       -- NULL when the role was never recorded
    applied_at          TIMESTAMPTZ NOT NULL,
    source              TEXT        NOT NULL,       -- CV_TOOL | CV_HISTORY | MANUAL
    folder              TEXT,                       -- the CV-<company>-<date> folder, to reopen it
    status              TEXT        NOT NULL DEFAULT 'Applied',
    response_status     TEXT        NOT NULL DEFAULT 'NONE',
    response_at         TIMESTAMPTZ,
    response_subject    TEXT,
    response_snippet    TEXT,
    response_checked_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One CV folder is one application, which makes re-running the backfill a no-op.
CREATE UNIQUE INDEX ux_application_folder ON application(folder) WHERE folder IS NOT NULL;
CREATE UNIQUE INDEX ux_application_job ON application(job_id) WHERE job_id IS NOT NULL;
CREATE INDEX ix_application_company ON application(company_id, applied_at DESC);

-- Carry over anything already marked on a job.
INSERT INTO application (company_id, job_id, title, applied_at, source, status,
                         response_status, response_at, response_subject, response_snippet,
                         response_checked_at)
SELECT j.company_id, j.id, j.title, COALESCE(j.applied_at, j.cv_generated_at, now()),
       'CV_TOOL', COALESCE(j.application_status, 'Applied'),
       j.response_status, j.response_at, j.response_subject, j.response_snippet,
       j.response_checked_at
  FROM job j
 WHERE j.applied_at IS NOT NULL OR j.cv_generated;

ALTER TABLE email_message ADD COLUMN application_id BIGINT REFERENCES application(id) ON DELETE SET NULL;
