# Working on Relay with an AI assistant

Relay's purpose is incremental development with understanding. Keep future changes aligned with that purpose.

- Start from a clearly named chunk and explain its outcome, scope, and definition of done.
- Read the relevant module before editing. Explain the existing flow in plain language.
- Implement a focused, reviewable change. Put unrelated follow-up work in separate chunks rather than bundling it into the current change.
- Keep domain rules in the application services/policies, transport adaptation in controllers, and presentation formatting in the web module.
- Keep learning context in the chunk guide: why this matters, the concept being learned, small steps, and review notes.
- Use `./mvnw verify` for meaningful verification; run the PostgreSQL migration test with Docker available before deployment.
- Report what changed, why it works, how it was checked, and what the developer should understand before continuing.
- Do not imply that assignment starts an agent, that reflection proves understanding, or that generated planning is supplied by an embedded model. The app exposes tools for the user's own assistant/runtime.
