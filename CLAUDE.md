# CLAUDE.md — Project Ground Rules

This file governs how Claude Code should behave while building this project.
Read this before starting any task, and re-check it whenever unsure.

Project: **Order Processing System with Live Operations Dashboard**
Time budget: **3 hours, hackathon preliminary round**

## Source of Truth
`PLAN.md`, `SCHEMA.md`, and `API.md` (in this repo root) are the authoritative spec.
If a requirement is unclear or missing from these files, **stop and ask** — do not
invent scope, features, fields, or routes that aren't defined there.

If code and the plan files ever disagree, the plan files win, unless I explicitly
tell you to update the plan.

## Do
- Refer back to PLAN.md / SCHEMA.md / API.md first whenever you have a doubt about
  a schema field, route name, status value, or workflow step.
- Ask a direct clarifying question when something is ambiguous, missing, or could
  be implemented multiple valid ways — before writing code.
- Confirm with me before any of the following:
  - Adding a new npm/pip/maven dependency not already listed in PLAN.md
  - Changing the schema or API contract from what SCHEMA.md/API.md define
  - Deleting or overwriting existing files
  - Making architectural decisions not covered in the plan files (e.g. switching
    from pessimistic locking to optimistic locking)
- State your assumptions explicitly if you must proceed without confirmation
  (e.g. minor naming choices) — never proceed silently.
- Point out inconsistencies, bugs, or scope gaps you notice, even if not asked.
- Keep changes scoped to what was asked. Don't refactor unrelated code without
  flagging it first.
- After each phase in PLAN.md, tell me exactly what changed, which files were
  touched, and what still needs my decision.
- Run the code before claiming it works. If you haven't run/tested something,
  say so explicitly.

## Do Not
- Do not assume unstated business logic — especially around inventory deduction,
  retry timing, or what counts as an "out of stock" failure. Ask.
- Do not hallucinate Spring Boot / JPA / React APIs or method signatures — verify
  against installed dependency versions before using them.
- Do not fabricate sample data or "placeholder" business logic and present it as
  final — mark it clearly as `// PLACEHOLDER` if used.
- Do not silently change the tech stack (Java version, Spring Boot version,
  Postgres vs MySQL, React vs Angular) without explicit confirmation.
- Do not claim something works, is tested, or is complete unless you actually
  ran/verified it.
- Do not install or suggest any third-party Claude Code skill, plugin, or
  package without first explaining what it does and confirming with me.
- Do not implement a "real" message-broker DLQ (Kafka/RabbitMQ). This project's
  DLQ is a bounded-retry-then-status-flip pattern, as defined in PLAN.md. This
  is a documented scope decision, not a shortcut to hide.

## Concurrency & Correctness — non-negotiable
- Inventory must **never** go negative, under any level of concurrent load.
- The locking mechanism must be the one specified in PLAN.md (pessimistic row
  lock via `SELECT ... FOR UPDATE` / `@Lock(PESSIMISTIC_WRITE)`) unless I say
  otherwise. Do not swap in a different concurrency strategy mid-build.
- Every order must end in exactly one terminal status: `COMPLETED`,
  `DEAD_LETTER`, or remain `PENDING`/`PROCESSING` only transiently.

## When Unsure
1. Check PLAN.md / SCHEMA.md / API.md first.
2. If still unclear, ask me a specific, direct question — not a vague
   "let me know if this looks right."
3. If you must make a judgment call to keep moving (time is tight — 3 hours
   total), say so explicitly, mark it, and flag it for my review rather than
   presenting it as settled.

## Communication Style
Be concise and direct. No filler confirmations like "Sure, I can help with
that!" — just do the work or ask the question. When reporting completed work,
state exactly what was changed, which files were touched, and what (if
anything) still needs my decision.

## Environment Notes
- Dev machine: 8GB RAM, no dedicated GPU. Do not suggest Docker Desktop for
  Postgres — use a natively installed local instance. Keep the React dev
  server and Spring Boot as the only two heavy processes running.
- Given the 3-hour budget, do not gold-plate: no auth system, no CI/CD, no
  containerization, no test coverage beyond a few smoke tests for the locking
  logic — unless PLAN.md says otherwise.
