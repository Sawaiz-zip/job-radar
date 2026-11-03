# Contributing

Thanks for helping. Bug reports, new job sources and better filters are all welcome.

## Setting up

Follow the [Quick start](README.md#quick-start). Unit tests need no database:

```bash
./mvnw test
cd frontend && npx ng build
```

## Adding a job source

1. Implement `JobSourceAdapter` in `com.jobradar.source`. Extend `AbstractHttpAdapter` so
   the per-host rate limit and User-Agent apply.
2. Add the new value to `SourceType`, and a Flyway migration if the database needs to know
   it.
3. If it is an ATS, teach `AtsDetector` how to probe for a tenant. A probe result must stay
   `GUESSED` until `DetectionVerifier` finds independent evidence.
4. Add a test with a saved response (see `StepStoneAdapterTest`), so the parser is tested
   without network access.

Only public data: no logins, no bot-protection bypass, and respect `robots.txt`.

## Ground rules

- **One write path for jobs.** Everything goes through `JobUpsertService`.
- **Filters hide, they do not delete.** A dropped job keeps its row and its reason.
- **Migrations are append-only.** Never edit a released file in `db/migration`; add a new one.
- **Personal settings stay out of commits.** `config/application-local.yml`,
  `config/profile.yaml`, `.env` and `seed/local/` are git-ignored; keep them that way.
- Write tests for behaviour, especially for filters, where a wrong drop is invisible.

## Pull requests

Keep them focused, explain the why in the description, and make sure CI is green.
