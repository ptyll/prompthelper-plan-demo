# Agent instructions

- Use Java 21 and the Maven Wrapper (`./mvnw` or `mvnw.cmd`).
- Keep the demo small and readable; do not add a frontend or unrelated domain features.
- Run `verify` after every code change.
- Tests must use temporary isolated SQLite databases.
- Startup and seed logic must never drop tables or delete existing rows.
- Use only fictional requester aliases and public-safe sample data.
- Never commit credentials, browser data, PromptHelper screenshots, private URLs, presentation assets, or files from the parent workspace.
- Implement only the endpoint or rule requested by the current PromptHelper plan phase.
