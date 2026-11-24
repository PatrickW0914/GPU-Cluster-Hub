# GPU Cluster Hub — CS 166 Phase 1

A PostgreSQL database for a shared graduate-student GPU cluster: which AI
models / checkpoints are available on which nodes, which environments run
them, GPU reservations with conflict detection and weekly group quotas, and
job / usage history.

## Rebuild from scratch (PostgreSQL 14+, Python 3.8+, run from this folder)

```bash
createdb gpuhub
psql -d gpuhub -f sql/01_schema.sql          # drops + recreates schema "gpuhub"
python3 data/generate_data.py                # optional: CSVs are already included
psql -d gpuhub -f data/load_data.sql
psql -d gpuhub -f tests/constraint_tests.sql # 46/46 PASS; database left unchanged
psql -d gpuhub -f sql/03_planned_queries.sql
```

Large dataset for Phase 2 indexing experiments (about 96k reservations, 259k jobs; loads in about 3 minutes):

```bash
python3 data/generate_data.py --scale large       # writes data_large/
createdb gpuhub_large
psql -d gpuhub_large -f sql/01_schema.sql
psql -d gpuhub_large -f data_large/load_data.sql
```

All tables live in schema `gpuhub` (`SET search_path TO gpuhub;`, or
`currentSchema=gpuhub` on the JDBC URL).

## Files

| Path | Phase 1 deliverable |
| --- | --- |
| `sql/01_schema.sql` | 4. Database creation script (DDL, constraints, triggers, views, indexes) |
| `data/*.csv`, `data/load_data.sql` | 5. Sample data + loader |
| `data/generate_data.py` | Synthetic data generator (deterministic, `--seed`, `--scale`) |
| `docs/gpuhub_er_diagram.pdf` | 2. ER diagram (regenerate with `python3 docs/er_diagram.py`) |
| `tests/constraint_tests.sql` | 6. Constraint test suite |
| `tests/test_output.txt` | 6. Captured output of a full clean run |
| `sql/03_planned_queries.sql` | Planned queries Q1–Q8 |

Deliverables 1 and 3 (requirements document; relational schema, FD and
normalization documentation) are in the Phase 1 design report.

## Trigger error codes

| SQLSTATE | Meaning |
| --- | --- |
| GH001 | GPU already booked for an overlapping window |
| GH002 | GPU in maintenance / retired, or node not UP |
| GH003 | Group weekly GPU-hour quota exceeded |
| GH004 | Reservation has no GPU |
| GH005 | Job outside its reservation window / reservation cancelled |
| GH006 | Model version not verified for the job's environment |
| GH007 | Node NVMe capacity exceeded by staged checkpoints |
| GH008 | Group PI must be Faculty |
