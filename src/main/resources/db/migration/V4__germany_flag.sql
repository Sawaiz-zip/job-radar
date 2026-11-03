-- A global board returns the whole world. Everything outside Germany is noise here,
-- so mark the relevant rows once instead of filtering with a long LIKE list on every read.
ALTER TABLE job ADD COLUMN in_germany BOOLEAN NOT NULL DEFAULT FALSE;

CREATE OR REPLACE FUNCTION job_is_in_germany(location TEXT) RETURNS BOOLEAN AS $$
  SELECT CASE
    WHEN location IS NULL THEN FALSE
    WHEN lower(location) ~ '(germany|deutschland|\mde\M)' THEN TRUE
    WHEN lower(location) ~ ('(berlin|munich|muenchen|münchen|hamburg|cologne|koeln|köln|frankfurt|'
       || 'stuttgart|duesseldorf|düsseldorf|dortmund|essen|leipzig|dresden|hannover|hanover|'
       || 'nuremberg|nuernberg|nürnberg|bremen|bonn|karlsruhe|mannheim|muenster|münster|wiesbaden|'
       || 'augsburg|mainz|jena|erfurt|ilmenau|heidelberg|darmstadt|ulm|walldorf|potsdam|freiburg|'
       || 'aachen|kiel|braunschweig|wolfsburg|neckarsulm|oberkochen|metzingen|boeblingen|böblingen|'
       || 'erlangen|ludwigsburg|monheim|neckarsulm|garching|ismaning|eschborn)') THEN TRUE
    ELSE FALSE
  END;
$$ LANGUAGE SQL IMMUTABLE;

UPDATE job SET in_germany = job_is_in_germany(location_raw);

CREATE INDEX ix_job_germany ON job(in_germany) WHERE in_germany;
