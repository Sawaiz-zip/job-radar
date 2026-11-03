-- Where an application actually stands. The CV tool marks a job when it writes a CV; the
-- Gmail sync fills in what the employer said back.
ALTER TABLE job
    ADD COLUMN applied_at        TIMESTAMPTZ,
    ADD COLUMN response_status   TEXT NOT NULL DEFAULT 'NONE',
        -- NONE | ACKNOWLEDGED | REJECTED | INTERVIEW | OFFER | UNCLEAR
    ADD COLUMN response_at       TIMESTAMPTZ,
    ADD COLUMN response_subject  TEXT,
    ADD COLUMN response_snippet  TEXT,
    ADD COLUMN response_checked_at TIMESTAMPTZ;

CREATE INDEX ix_job_applied ON job(applied_at DESC) WHERE applied_at IS NOT NULL;
CREATE INDEX ix_job_response ON job(response_status) WHERE response_status <> 'NONE';

-- One row per mailbox. A refresh token is the only thing worth keeping; access tokens are
-- short lived and fetched on demand.
CREATE TABLE gmail_account (
    id             INT PRIMARY KEY DEFAULT 1,
    email          TEXT,
    refresh_token  TEXT,
    connected_at   TIMESTAMPTZ,
    last_sync_at   TIMESTAMPTZ,
    last_error     TEXT,
    CONSTRAINT ck_single_account CHECK (id = 1)
);

-- Messages already looked at, so a re-sync is cheap and never double-counts.
CREATE TABLE email_message (
    message_id   TEXT PRIMARY KEY,
    job_id       BIGINT REFERENCES job(id) ON DELETE SET NULL,
    company_id   BIGINT REFERENCES company(id) ON DELETE SET NULL,
    from_address TEXT,
    subject      TEXT,
    received_at  TIMESTAMPTZ,
    verdict      TEXT,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_email_company ON email_message(company_id);
