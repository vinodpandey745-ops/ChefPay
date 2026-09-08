# ChefPay Documentation Index

This `docs/` folder is the full documentation package for ChefPay: what it does, how it's built,
how to run it, and how to deploy and scale it. Start with whichever guide matches what you're
trying to do right now.

| Guide | Read this if you are... |
|---|---|
| [`FUNCTIONAL_GUIDE.md`](./FUNCTIONAL_GUIDE.md) | ...the owner, a manager, staff, or preparing a client demo, and want to know what each screen does and how it's used day to day. Includes screenshots of every module. |
| [`TECHNICAL_GUIDE.md`](./TECHNICAL_GUIDE.md) | ...a developer who needs to make a change after the product is live — architecture, conventions, exactly where to edit SMTP/AI integration, and the recommended pattern for any brand-new integration. |
| [`DATA_DICTIONARY.md`](./DATA_DICTIONARY.md) | ...looking for the exact columns, types, and relationships of a specific database table. Covers all 47 entities, cross-checked against the Flyway migrations. |
| [`DEPLOYMENT_GUIDE.md`](./DEPLOYMENT_GUIDE.md) | ...setting up a local workspace, building the deployable server JAR, choosing a production database, scaling for a larger client, or deploying to Oracle Cloud. Also has the honest, direct answer on URL availability (see below). |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | ...the original phase-by-phase engineering design document from early development (Phases 1–5e). Still useful background, but `TECHNICAL_GUIDE.md` and `DATA_DICTIONARY.md` are the up-to-date references as of Round 18. |
| `../README.md` (repo root) | ...want the round-by-round change history (every feature added, in order, Round 9 through Round 18). |
| [`screenshots/`](./screenshots/) | Raw screenshots referenced by the guides above, captured from a real build of the current web app. |

## The one question every reader asks first: "what URL do I use right now?"

There isn't one yet. ChefPay has only ever run inside local development processes during
building and testing — it has never been deployed anywhere with a real, internet-reachable
address. `DEPLOYMENT_GUIDE.md` gives the exact, step-by-step path to get a real URL (Oracle
Cloud deployment section) — until that's done, there is nothing to browse to from outside this
project.

## Quick map of what exists in this package

- Full source for all four Maven modules (`chefpay-plugin-api`, `chefpay-core`, `chefpay-server`,
  `chefpay-javafx`) and the React frontend (`chefpay-web`).
- The frontend is already built and embedded at
  `chefpay-server/src/main/resources/static/app` — current as of Round 18 — so the very next
  `mvn package` run (see `DEPLOYMENT_GUIDE.md`) produces a server JAR that serves this exact web
  app. Re-run `npm run build` in `chefpay-web` first only if you make further frontend changes.
- Four documentation guides plus this index, a full data dictionary, and 14 screenshots.
