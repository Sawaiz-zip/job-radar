-- When ATS detection last asked about this company. A miss is stamped too, so each scan's
-- detection slice moves on through the registry instead of re-asking the same companies.
ALTER TABLE company ADD COLUMN probed_at timestamptz;

-- The few companies that were probed before this column existed keep their place.
UPDATE company SET probed_at = detected_at WHERE detected_at IS NOT NULL;

-- DSV was confirmed as Workday without the site name and data centre the adapter needs, so
-- it was skipped silently on every scan. Both read from its careers page on 2026-10-03:
-- https://dsvgruppe.wd103.myworkdayjobs.com/de-DE/DSV
UPDATE company SET ats_site = 'DSV', ats_region = 'wd103'
 WHERE ats_type = 'WORKDAY' AND ats_tenant = 'dsvgruppe' AND ats_site IS NULL;

-- Neither of these is a SuccessFactors list page any more (checked 2026-10-03), so the
-- adapter could only ever fail. They are parked with the reason rather than left failing:
--  * jobs.sap.com redirects /search/ to a Next.js site that renders its list in JavaScript
--    (its job ids are SmartRecruiters ids, spread over several per-entity tenants).
--  * 50hertz.com is a maintenance page; the real board, jobs.eliagroup.eu, also renders its
--    list client-side. Their German postings still arrive through BA and StepStone.
UPDATE company
   SET enabled = false, status = 'UNSUPPORTED',
       notes = concat_ws(E'\n', notes,
               'Parked 2026-10-03: career site moved to a JavaScript-rendered platform with no '
               || 'list the SuccessFactors adapter can read. Jobs still arrive via BA/StepStone.')
 WHERE ats_type = 'SUCCESSFACTORS' AND ats_tenant IN ('jobs.sap.com', '50hertz.com');
