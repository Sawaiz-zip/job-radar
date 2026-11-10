# Configuration reference

Defaults live in `src/main/resources/application.yml`. Put your own values in
`config/application-local.yml` (start from `config/application-local.example.yml`); it is
git-ignored and loaded on top of the defaults, so it only needs the keys you change. Lists
replace the default list entirely rather than adding to it.

Secrets go in a `.env` file in the project root (also git-ignored), one `KEY=value` per
line. Environment variables with the same names work too.

## General

| Key | Default | Meaning |
|---|---|---|
| `jobradar.contact-email` | empty | Added to the User-Agent so site operators can reach you. Please set it. |
| `jobradar.per-host-rps` | `1` | Requests per second to any one host. |
| `jobradar.close-after-missing-runs` | `2` | A job missing from this many scans in a row is marked closed. |
| `jobradar.repost-window-days` | `90` | A closed job that reappears within this window reopens the old row. |
| `jobradar.seed-dir` | `seed` | Folder with `companies.csv` and `search_terms.csv`, imported on every start. |
| `jobradar.profile-path` | `config/profile.yaml` | Your CV as YAML, used only by AI scoring. Format: `config/profile.example.yaml`. |
| `jobradar.cv-output-dir` | empty | Optional folder of `CV-<company>-<yyyy>-<mm>-<dd>` directories to import as application history. |

## Filters (`jobradar.filters`)

| Key | Default | Meaning |
|---|---|---|
| `max-years-required` | `0` | Hide postings asking for more years than this. `0` means no limit. |
| `max-german-level` | `NATIVE` | Hide postings requiring more German. `NONE`, `A1`, `A2`, `B1`, `B2`, `C1`, `C2`, `NATIVE`. |
| `drop-senior-titles` | `true` | Hide titles listed in `senior-titles`. |
| `senior-titles` | staff, principal, head of, director, lead, architect, ... | Whole-word title patterns. |
| `include-student-roles` | `false` | Keep Werkstudent, internship, Praktikum and Ausbildung roles. |
| `include-graduate-programmes` | `true` | Keep graduate and trainee schemes whose text is technical. |
| `title-must-match` | software-engineering words | The title must contain one of these. Matched anywhere in the title (no word boundaries), so `entwickl` matches "Softwareentwickler". |
| `title-exclude` | other disciplines | Whole-word patterns for titles that belong to a different discipline. |
| `boosts` | none | `{pattern, points}` pairs. Each pattern found in the title adds its points to the rule score (0-100). Negative points sort a match lower. |

All title patterns are case-insensitive Java regular expressions. In YAML, write a regex
backslash twice inside double quotes: `"\\bjava\\b"`.

## Job boards

| Key | Default | Meaning |
|---|---|---|
| `jobradar.ba.queries` | four software searches | Search words for the Bundesagentur für Arbeit. Keep the list short; it matches loosely. |
| `jobradar.ba.max-detail-fetches` | `250` | Upper bound on description fetches per scan. Jobs already stored with a description are skipped and do not count. |
| `jobradar.ba.published-within-days` | `28` | Read every page of postings published in this window (1-100 days), not just the first page by relevance. |
| `jobradar.ba.max-pages` | `10` | Pages per query, 100 results each. |
| `jobradar.stepstone.keywords` | four software slugs | Slugs as in `stepstone.de/jobs/<slug>`. One page (25 postings) per keyword. |
| `jobradar.stepstone.max-detail-fetches` | `120` | Upper bound on description fetches per scan. |
| `jobradar.detect.per-scan` | `30` | Companies probed for an ATS before each full scan. |
| `jobradar.detect.retry-days` | `30` | Days before a company with no board found is probed again. |

## AI scoring (`jobradar.llm`)

| Key | Default | Meaning |
|---|---|---|
| `scoring-enabled` | `false` | Score new jobs with a model after the rule filters. |
| `model` | a cheap OpenRouter model | Any OpenRouter model id. |
| `api-key` | `${OPENROUTER_API_KEY}` | Usually set through `.env`. |
| `env-file` | `.env` | Where to look for `OPENROUTER_API_KEY` if it is not in the environment. |
| `ideal-match` | empty | One sentence describing the jobs you want; added to the scoring instructions. |
| `budget-per-run` | `1.00` | Hard stop, in US dollars, for one scoring run. |
| `max-estimated-cost` | `0.50` | After five jobs the run's total is projected; above this, scoring stops. |

## Mail (`jobradar.mail`, `jobradar.gmail`, `jobradar.imap`)

See [MAIL.md](MAIL.md). Secrets: `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `IMAP_USER`,
`IMAP_PASSWORD`.

## Seed files

`companies.csv` columns: `name, normalized_name, category, cities, website, ats_hint,
priority, language, origin, notes`. Only `name` is required. `priority` is `A`, `B` or `C`
and sets the order companies are listed and scanned in.

`search_terms.csv` columns: `text, group, kind, active`. Rows with `kind` `INCLUDE` become
the tag bar: a title matches a tag when all of the tag's words appear in it, and tags with
the same `group` can be switched on and off together. The other kinds in the starter file
(`KEEP`, `DOWNRANK`, `DROP`, `FLAG_LANGUAGE`, `FLAG_AGENCY`) are kept as a reference list;
the work they describe is done by the filters above.

Both files are imported idempotently on every start, so restarting never creates
duplicates. Terms and companies added in the UI are stored in the database and are not
written back to the CSV files.
