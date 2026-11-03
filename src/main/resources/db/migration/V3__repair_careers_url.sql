-- An early detection run wrote the probe URL into careers_url. Verification then
-- fetched that ATS URL, found the tenant inside it and "confirmed" the guess against
-- itself. Clear those, and drop any confirmation that rested on that circular evidence.
UPDATE company
   SET careers_url = NULL
 WHERE careers_url ~* '(greenhouse\.io|lever\.co|ashbyhq\.com|jobs\.personio\.|recruitee\.com|myworkdayjobs\.com)';

UPDATE company
   SET detection_confidence = 'GUESSED',
       detection_evidence = 'requeued: earlier evidence was self-referential'
 WHERE detection_confidence = 'CONFIRMED'
   AND detection_evidence LIKE 'linked from%';
