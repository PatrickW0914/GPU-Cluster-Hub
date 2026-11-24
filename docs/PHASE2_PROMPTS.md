# Phase 2 — prompts for Claude Code

Paste these into Claude Code (in VS Code, opened on the `gpu-cluster-hub` folder) **one at a
time**. Wait for each milestone to finish, check the "Done when" list yourself, commit to git,
then move on. `CLAUDE.md` in the repo root gives Claude Code the project rules automatically.

Tips
- Start each milestone in plan mode (or say "plan first, don't edit yet"), read the plan, then approve.
- Review the diff before accepting. If something is unclear, ask "explain this file like I'll be
  asked about it in the demo" — both teammates must be able to explain the code.
- `git init` and commit after every milestone so you can roll back.

---

## M0 — Orientation (no code changes)

```
Read CLAUDE.md, README.md, sql/01_schema.sql, sql/03_planned_queries.sql and tests/constraint_tests.sql.
Then rebuild the database from scratch with the commands in CLAUDE.md, run the constraint tests and
the planned queries, and report: table row counts, test result (expect 46/46), and whether all 8
queries return rows. Do not change any files. If PostgreSQL isn't running, tell me how to start it on
macOS (Homebrew postgresql@16) instead of guessing.
```
Done when: 46/46 PASS, 8 queries return rows, no files changed.

## M1 — Java project scaffold + DB connection

```
Create the Maven project under java/ (Java 17, PostgreSQL JDBC driver, package edu.ucr.cs166.gpuhub).
Add: config loading from java/config/db.properties with env-var overrides (see CLAUDE.md), a committed
db.properties.example, a .gitignore entry for db.properties, a Database class that opens connections with
currentSchema=gpuhub, and SqlErrors that maps SQLSTATEs to friendly messages exactly as the table in
CLAUDE.md. Add a tiny smoke-test main that connects and prints the number of ai_model rows. Show me how
to build and run it.
```
Done when: `mvn -q package` works and the smoke test prints 28.

## M2 — Data access layer

```
Write one DAO class per principal entity (users, groups+membership, nodes, GPU types, GPUs, models,
model versions, environments, reservations, jobs). Each DAO: insert (RETURNING id), findById, search
with optional filters, update, delete — all with PreparedStatement. Model versions use the composite
key (model_id, version_tag). Creating a research group must insert the group and the PI membership in
one transaction. No UI yet. Add a quick test class that exercises one insert/update/delete per DAO
against the gpuhub database and cleans up after itself.
```
Done when: the test class runs green and leaves row counts unchanged.

## M3 — GUI shell and navigation

```
Build the Swing main window: a left navigation (or tabs) with Catalog, GPU Booking, Reservations & Jobs,
Groups & Users, Inventory (admin), Queries & Reports, and a status bar showing connection status and the
"current time" setting (default 2026-11-02 09:00, toggle to real now). Use one consistent layout pattern
for every entity screen: search filters on top, results JTable in the middle, Add/Edit/Delete buttons,
and a form dialog for add/edit. Show success messages and SqlErrors-mapped error dialogs. Keep SQL out of
UI classes.
```
Done when: the app opens, every tab exists, connection status shows.

## M4 — CRUD screens

Do these one per prompt, in this order: Users & Groups (incl. memberships), Inventory (nodes, GPU
types, GPUs), Catalog (models, versions, environments, compatibility, staged copies), Jobs.

```
Implement the <SCREEN NAME> screen using the layout from M3: search with relevant filters (including at
least one search across related tables, e.g. "nodes hosting this model version"), add/edit with input
validation, delete with a clear message when blocked by a foreign key or business rule (e.g. "user has
reservations — deactivate instead"). Then list 3 things I should try in the GUI to verify it, including
one that should fail.
```
Done when: insert, search, update, and delete all work, and the failure cases show friendly messages.

## M5 — Booking transaction (required item)

```
Implement the GPU Booking screen: choose user + one of their groups, time window, node, and one or more
GPUs (show each GPU's type and VRAM); book them as ONE transaction exactly as described in CLAUDE.md
(remember deferred constraints can fail at commit). Also support editing times and cancelling a booking.
Afterwards give me a demo script that shows: (1) a successful 2-GPU booking, (2) a booking where the 2nd
GPU is already taken → error and no rows left behind (verify with a count before/after), (3) a booking that
exceeds the group's weekly quota.
```
Done when: all three demo cases behave as described.

## M6 — Queries & Reports screen (15% of the grade)

```
Port Q1–Q8 from sql/03_planned_queries.sql into the app as PreparedStatements with GUI inputs for their
parameters (model + version picker, hours, date/"current time"). Show results in a sortable JTable with
readable column names, plus a one-line description of what each query answers and which SQL concept it
demonstrates (join, aggregate, correlated subquery, relational division, ranking, report). Save the final
SQL of each query in sql/queries/Q1.sql … Q8.sql for submission.
```
Done when: every query runs from the GUI and the results match psql.

## M7 — Advanced feature: GPU slot finder

```
Build the GPU slot finder (advanced feature): inputs = model version, duration in hours, search horizon
(days), optional minimum GPU type. It finds the earliest slots on nodes that host the checkpoint, have an
environment verified for it whose CUDA <= node max_cuda_version, and have enough free GPUs whose combined
VRAM covers min_vram_gb (start from Q8). Show the top candidates per node; a "Book this slot" button
pre-fills and runs the M5 booking transaction. Then write docs/advanced_feature.md: why it's useful, why it's
technically challenging, how it works, and what the database does (range overlap, triggers, locking).
```
Done when: the slot finder suggests a slot and booking it succeeds; trying to book it twice fails with GH001.

## M8 — Indexing and performance analysis (on the large dataset)

```
Using gpuhub_large, write sql/04_index_experiments.sql that, for at least these queries — Q2 weekly quota
usage, the GH001 overlap check / Q8 slot search, and one job report (Q5 or Q7) — runs EXPLAIN (ANALYZE,
BUFFERS) WITHOUT the relevant index (DROP INDEX inside a transaction, then ROLLBACK) and WITH it. Run it,
then write docs/indexing_results.md: why each index should help, the index definition, both plans
(trimmed), execution times before/after, explanation, and trade-offs (write cost, storage). If an index
doesn't help, say so and explain why (e.g. small table, low selectivity). Don't permanently change the schema.
```
Done when: the before/after numbers are in docs/indexing_results.md and the schema is unchanged.

## M9 — Error handling and security pass

```
Audit the whole app against the course's error-handling list: missing/invalid input, duplicates,
nonexistent references, deleting referenced records, DB connection/query errors (stop PostgreSQL and try),
failed transactions. Confirm every SQL statement is a PreparedStatement and no credentials are in source.
Fix gaps, then produce docs/testing_checklist.md: a table of test cases (action, expected result, actual
result) covering successful operations, invalid inputs, constraint violations, and failed transactions —
leave a column for screenshot filenames.
```
Done when: every row in the checklist passes and you've taken the screenshots.

## M10 — Final report draft + run instructions

```
Draft docs/final_report.md with the required sections: Introduction; Requirements & design (ER diagram,
schema, business rules, assumptions); Constraints (FDs, keys, integrity constraints); Implementation
(database, Java/JDBC, GUI, architecture diagram); SQL functionality (each query, purpose, representative
results); Testing (from the checklist, with screenshot placeholders); Team contributions (leave for us to
fill) and References (PostgreSQL JDBC driver, any libraries, AI assistance). Reuse the Phase 1 material
where it still applies and note any schema changes from docs/SCHEMA_CHANGES.md. Update README.md with
step-by-step setup and run instructions for someone starting from a fresh Mac.
```
Done when: someone else can follow the README on a fresh machine.

## M11 — Demo prep (15 min + Q&A, both teammates present)

```
Write docs/demo_script.md: a 15-minute run-through split between two presenters covering the problem and
users, database design, GUI workflows, insert/search/update/delete, a multi-table join and an aggregate
query, one integrity/error-handling example, the booking transaction rollback, the slot finder, and the
indexing results. Add 10 likely instructor questions with short answers (normalization, why triggers,
deferred constraints, why each index, how SQL injection is prevented).
```
