# Demo security boundary

GearReserve is a local educational demo, not a production-ready or publicly hosted service.

- No authentication or authorization is implemented.
- The server binds to `127.0.0.1` by default.
- The repository stores no secrets.
- `.env` files and SQLite databases are ignored.
- All documented aliases and records are fictional.
- CI builds and tests the code but does not deploy it.
