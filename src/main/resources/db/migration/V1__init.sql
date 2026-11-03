-- Job Radar core schema.
-- Invariant (constitution #7): uniqueness lives in indexes, not only in service code.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE company (
    id                BIGSERIAL PRIMARY KEY,
    name              TEXT        NOT NULL,
    normalized_name   TEXT        NOT NULL,
    primary_domain    TEXT,
    careers_url       TEXT,
    ats_type          TEXT        NOT NULL DEFAULT 'UNKNOWN',
    ats_tenant        TEXT,
    ats_site          TEXT,
    ats_region        TEXT,
    category          TEXT,
    cities            TEXT,
    language          TEXT,
    priority          CHAR(1)     NOT NULL DEFAULT 'B',
    user_status       TEXT        NOT NULL DEFAULT 'NOT_STARTED',
    status            TEXT        NOT NULL DEFAULT 'OK',
    enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    origin            TEXT        NOT NULL DEFAULT 'MANUAL',
    parent_company_id BIGINT      REFERENCES company(id),
    last_success_at   TIMESTAMPTZ,
    last_job_count    INT,
    consecutive_errors INT        NOT NULL DEFAULT 0,
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- L-company: strong keys. Partial indexes so many NULLs stay legal.
CREATE UNIQUE INDEX ux_company_domain ON company(primary_domain) WHERE primary_domain IS NOT NULL;
CREATE UNIQUE INDEX ux_company_ats    ON company(ats_type, ats_tenant) WHERE ats_tenant IS NOT NULL;
CREATE UNIQUE INDEX ux_company_norm   ON company(normalized_name);
CREATE INDEX ix_company_norm_trgm ON company USING gin (normalized_name gin_trgm_ops);

CREATE TABLE company_alias (
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    alias_normalized TEXT   NOT NULL UNIQUE
);

CREATE TABLE job (
    id                 BIGSERIAL PRIMARY KEY,
    company_id         BIGINT      NOT NULL REFERENCES company(id) ON DELETE CASCADE,
    title              TEXT        NOT NULL,
    title_normalized   TEXT        NOT NULL,
    fingerprint        TEXT        NOT NULL,
    city_normalized    TEXT,
    location_raw       TEXT,
    remote_type        TEXT,
    employment_type    TEXT,
    seniority          TEXT,
    description_text   TEXT,
    description_hash   TEXT,
    apply_url          TEXT,
    -- posted_at is nullable on purpose: several ATS feeds never report it.
    -- Sort on COALESCE(posted_at, first_seen_at) and show which one was used.
    posted_at          TIMESTAMPTZ,
    first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at          TIMESTAMPTZ,
    state              TEXT        NOT NULL DEFAULT 'OPEN',
    reposted           BOOLEAN     NOT NULL DEFAULT FALSE,
    previous_job_id    BIGINT      REFERENCES job(id),
    cv_generated       BOOLEAN     NOT NULL DEFAULT FALSE,
    cv_generated_at    TIMESTAMPTZ,
    application_status TEXT,
    dismissed          BOOLEAN     NOT NULL DEFAULT FALSE,
    snoozed_until      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- L3: one open job per (company, fingerprint). Closed rows are excluded so a
-- repost outside the reopen window can legally create a new row.
CREATE UNIQUE INDEX ux_job_fingerprint_open ON job(company_id, fingerprint) WHERE state = 'OPEN';
CREATE INDEX ix_job_company     ON job(company_id);
CREATE INDEX ix_job_state       ON job(state) WHERE state = 'OPEN';
CREATE INDEX ix_job_title_trgm  ON job USING gin (title_normalized gin_trgm_ops);
CREATE INDEX ix_job_dates       ON job(COALESCE(posted_at, first_seen_at) DESC);

CREATE TABLE job_source (
    id            BIGSERIAL PRIMARY KEY,
    job_id        BIGINT      NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    source_type   TEXT        NOT NULL,
    tenant        TEXT,
    external_id   TEXT,
    original_url  TEXT        NOT NULL,
    canonical_url TEXT        NOT NULL,          -- L1
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    missing_count INT         NOT NULL DEFAULT 0,
    raw_payload   JSONB
);

CREATE UNIQUE INDEX ux_source_canonical ON job_source(canonical_url);
-- L2
CREATE UNIQUE INDEX ux_source_external ON job_source(source_type, tenant, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX ix_source_job ON job_source(job_id);

CREATE TABLE possible_duplicate (
    id        BIGSERIAL PRIMARY KEY,
    job_id_a  BIGINT NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    job_id_b  BIGINT NOT NULL REFERENCES job(id) ON DELETE CASCADE,
    score     NUMERIC(4,3) NOT NULL,
    status    TEXT NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dup_order CHECK (job_id_a < job_id_b),
    CONSTRAINT ux_dup_pair UNIQUE (job_id_a, job_id_b)
);

CREATE TABLE job_match (
    job_id           BIGINT PRIMARY KEY REFERENCES job(id) ON DELETE CASCADE,
    rule_score       INT,
    -- two distinct numbers, deliberately: fit is "does this match what he does",
    -- chance is "would they actually interview him".
    fit_score        INT,
    chance_score     INT,
    reason           TEXT,
    german_level     TEXT,
    years_required   INT,
    stack_overlap    JSONB,
    missing_skills   JSONB,
    salary_min       INT,
    salary_max       INT,
    visa_note        TEXT,
    deadline         DATE,
    cv_tips          JSONB,
    flags            JSONB,
    description_hash TEXT,
    model            TEXT,
    state            TEXT NOT NULL DEFAULT 'OK',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_match_chance ON job_match(chance_score DESC NULLS LAST);

CREATE TABLE source_run (
    id          BIGSERIAL PRIMARY KEY,
    company_id  BIGINT REFERENCES company(id) ON DELETE CASCADE,
    source_type TEXT NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    http_status INT,
    job_count   INT,
    new_count   INT,
    error       TEXT,
    duration_ms BIGINT
);

CREATE INDEX ix_run_company ON source_run(company_id, started_at DESC);

CREATE TABLE page_watch (
    company_id      BIGINT PRIMARY KEY REFERENCES company(id) ON DELETE CASCADE,
    url             TEXT NOT NULL,
    content_hash    TEXT,
    last_checked_at TIMESTAMPTZ,
    last_changed_at TIMESTAMPTZ
);

CREATE TABLE company_suggestion (
    id              BIGSERIAL PRIMARY KEY,
    normalized_name TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    seen_count      INT  NOT NULL DEFAULT 1,
    sample_job_urls JSONB,
    status          TEXT NOT NULL DEFAULT 'OPEN'
);

CREATE TABLE ingest_request (
    client_request_id TEXT PRIMARY KEY,
    response          JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The editable tag bar. Seeded from the 'Search terms' sheet, owned by the user.
CREATE TABLE search_term (
    id       BIGSERIAL PRIMARY KEY,
    text     TEXT NOT NULL,
    term_group TEXT NOT NULL,
    kind     TEXT NOT NULL,   -- INCLUDE | KEEP | DOWNRANK | DROP | FLAG_LANGUAGE | FLAG_AGENCY
    active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_term UNIQUE (text, kind)
);
