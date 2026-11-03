-- BA and StepStone only list German postings, but job_is_in_germany() recognises about sixty
-- cities by name, so jobs in Bochum, Neuss or Bad Hersfeld were flagged as abroad and hidden
-- from the default Germany-only view (66 of 212 BA jobs, 14 of 47 StepStone jobs). New rows
-- now take the source as evidence (JobUpsertService); this repairs the existing ones.
UPDATE job j SET in_germany = TRUE
 WHERE NOT j.in_germany
   AND EXISTS (SELECT 1 FROM job_source s
                WHERE s.job_id = j.id AND s.source_type IN ('BA', 'STEPSTONE', 'SMARTRECRUITERS'));
