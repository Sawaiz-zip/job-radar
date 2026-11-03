-- Tenant slugs are guessed from the company name, which collides: google.recruitee.com
-- is a demo board, amazon.jobs.personio.de is a Lorem-ipsum test tenant. A guess is
-- therefore never scanned until something independent confirms it belongs to the company.
ALTER TABLE company
    ADD COLUMN detection_confidence TEXT NOT NULL DEFAULT 'NONE',   -- NONE | GUESSED | CONFIRMED | REJECTED
    ADD COLUMN detection_evidence   TEXT,
    ADD COLUMN detected_at          TIMESTAMPTZ;

UPDATE company SET detection_confidence = 'GUESSED', detected_at = now()
 WHERE ats_tenant IS NOT NULL;

CREATE INDEX ix_company_confidence ON company(detection_confidence);
