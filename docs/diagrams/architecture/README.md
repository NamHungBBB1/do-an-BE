# Architecture overview (C4 model, PlantUML)

System overview of FinTeen in the C4 model (c4model.com, Simon Brown), drawn with the C4-PlantUML library that ships in the PlantUML standard library (`!include <C4/...>`, no network access needed).
Render: `java -jar plantuml.jar -tpng <file>.puml`. Legend: dark blue = person, blue = FinTeen (system or container), grey = external system, dashed box = boundary; each arrow reads "source does label", with the protocol in brackets.

## `c4-context.puml`: Level 1, System Context

FinTeen as one box, with the people who use it and the external services it depends on.
People: Learner (child or student on a seat, no account; guests can play without saving), Parent / Teacher (paying adult), Studio staff (Editor, Reviewer, Manager) and Administrator.
External services: Firebase Authentication (Google sign-in), PayOS (VietQR payment and paid-order webhook), Brevo (e-mail with one-time codes), Google Gemini (suggests how chapter lines are read aloud), Cloudflare Pages (earlier image and narration host, kept as a fallback).

## `c4-container.puml`: Level 2, Container

The deployable parts of FinTeen. The web app (React, on Vercel) holds the game player, the adult console and the Studio. Everything else runs on one Linux server: a Cloudflare Tunnel and nginx form the single public entry point, nginx serves asset files directly and forwards API calls to the Spring Boot API, which uses PostgreSQL and the asset folder.
Main data flows: (1) a learner finishes a chapter and the web app sends the chapter's choices in one batch; the API recomputes the results from the frozen release JSON stored in the database and ignores totals sent by the client. (2) An adult buys a plan: the API creates a PayOS order, and PayOS calls the webhook when the order is paid. (3) Reports are read with an account token and only by the group owner. (4) Studio: draft, then review, then publish an immutable release. (5) Sign-in uses Google through Firebase (the API checks the ID token) or e-mail and password; one-time codes are sent through Brevo.
GitHub Actions deploys the API after the tests pass on `main`. It dumps the database first and rolls back if the health check fails.
