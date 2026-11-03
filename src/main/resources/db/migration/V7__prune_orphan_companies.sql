-- Board scans create a company row per employer they mention, which is correct: a job
-- needs an employer. What was not correct is that deleting those jobs left the companies
-- behind. 376 board-discovered companies had no job row at all, crowding the registry
-- with employers that were never watched and no longer had anything to show.
DELETE FROM company c
 WHERE c.origin = 'BOARD'
   AND c.ats_tenant IS NULL
   AND NOT EXISTS (SELECT 1 FROM job j WHERE j.company_id = c.id)
   AND NOT EXISTS (SELECT 1 FROM application a WHERE a.company_id = c.id);

-- A board that names a different country is a different legal entity. "adesso
-- Netherlands" was accepted for adesso SE because the first word matched, and German
-- searches were being answered with Dutch jobs.
UPDATE company
   SET ats_type = 'UNKNOWN', ats_tenant = NULL,
       detection_confidence = 'REJECTED',
       detection_evidence = 'board belongs to a different country entity'
 WHERE detection_evidence ILIKE '%board name%'
   AND detection_evidence ~* '(netherlands|belgium|austria|switzerland|poland|spain|france|uk|usa)';
