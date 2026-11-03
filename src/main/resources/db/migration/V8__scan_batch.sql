-- "What did THIS scan bring in" needs a scan to be a thing with a start and an end.
-- Until now a scan was only a scatter of source_run rows, so the question could only be
-- answered by guessing at a time window.
CREATE TABLE scan_batch (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT        NOT NULL,          -- ALL | COMPANY | BOARDS
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    sources     INT NOT NULL DEFAULT 0,
    jobs_seen   INT NOT NULL DEFAULT 0,
    jobs_new    INT NOT NULL DEFAULT 0,
    failures    INT NOT NULL DEFAULT 0
);

CREATE INDEX ix_scan_batch_started ON scan_batch(started_at DESC);

ALTER TABLE source_run ADD COLUMN batch_id BIGINT REFERENCES scan_batch(id) ON DELETE SET NULL;

-- Which scan first saw a job. first_seen_at already says WHEN, but a timestamp cannot be
-- compared to a scan without knowing that scan's window, and two scans can overlap a minute.
ALTER TABLE job ADD COLUMN first_seen_batch BIGINT REFERENCES scan_batch(id) ON DELETE SET NULL;

CREATE INDEX ix_job_first_batch ON job(first_seen_batch) WHERE first_seen_batch IS NOT NULL;
