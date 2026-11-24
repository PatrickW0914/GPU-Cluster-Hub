# Indexing and performance analysis

**Setup.** Database `gpuhub_large` (95,706 reservations, 216,860 reservation-GPU rows, 259,203 jobs,
312,946 model loads, three years of history). PostgreSQL 16.16, default settings, Apple M2 Pro, 16 GB RAM.

**Method.** [`sql/04_index_experiments.sql`](../sql/04_index_experiments.sql) runs every query with and
without an index. The index is dropped in a transaction that is rolled back, so the schema is unchanged.
Times are the **median of 5 runs** after a warm-up run, so all data is in memory. Full output with all
plans: [`indexing_output.txt`](indexing_output.txt). The plans below are trimmed to the decisive lines, and
come from a single run, so their execution times differ slightly from the medians.

```bash
psql -X -d gpuhub_large -f sql/04_index_experiments.sql      # about 4 minutes
```

## Summary

| Query | Index | Without | With | Effect |
|---|---|---:|---:|---|
| Q6 booked but idle | `idx_job_reservation` | 49.3 s | 11.2 ms | **about 4,400x faster** |
| GH001 overlap check (busy week) | `idx_res_period` | 0.740 ms | 0.039 ms | **19x faster** |
| GH003 quota of one group | `idx_res_group_start` | 0.545 ms | 0.027 ms | **20x faster** |
| Slot finder | `idx_res_period` | 489 ms | 60.2 ms | **8x faster** |
| Q2 quota of all groups | `idx_res_group_start` | 5.8 ms | 5.8 ms | none: wrong column order |
| Q8 slot search | `idx_res_period` | 532 ms | 532 ms | none: the planner does not use it |
| Q7 weekly model loads | `idx_job_submit` | 14.9 ms | 29.5 ms | **slower**: the planner's cost setting |
| Q5 top consumers | none exists | 21.7 ms | 17.8 ms (what if) | small |
| Nodes hosting a version | `idx_hosts_version` | 0.011 ms | 0.012 ms | none: 279 rows |

An index helps when it matches the query's condition **and** finds few rows. The four cases where
it did not help are explained below, because each has a different reason.

---

## 1. Q2 (quota of all groups) and `idx_res_group_start`: no help

```sql
CREATE INDEX idx_res_group_start ON reservation (group_id, start_time);
```

**Expectation.** Q2 reads one week, 583 of 95,706 reservations, so an index containing `start_time`
looked useful.

**With the index** (the plan without it is the same):

```
  Workers Launched: 1
    ->  Parallel Seq Scan on reservation r  (estimated rows=260) (actual time=3.958..4.015 rows=262 loops=2)
      Rows Removed by Filter: 47590
Execution Time: 6.516 ms
```

**Why it does not help.** The B-tree is sorted by `group_id` first. Q2 has no condition on `group_id`, so the
matching rows are scattered over the whole index, once per group. Reading the table in
parallel is cheaper. With an index that starts with `start_time` (created and rolled back as a what-if test):

```
  ->  Index Scan using what_if_start on reservation r  (estimated rows=440) (actual time=0.009..0.077 rows=525 loops=1)
    Index Cond: ((start_time >= '2026-11-02 00:00:00') AND (start_time < '2026-11-09 00:00:00'))
Execution Time: 1.146 ms
```

That is 5 times faster (5.8 ms → 1.1 ms). We did not add it: Q2 is a report
that runs rarely and takes 6 ms, while every index slows down every booking (section 8).

## 2. Quota of one group (GH003) and `idx_res_group_start`: 20x faster

This is what the index was made for. The GH003 trigger and the booking screen add up one group's
GPU-hours in one week, so both index columns are used: `group_id = ...` and a range of `start_time`.

**Without:** the next best index finds all bookings the group ever made, and 2,527 are thrown away.

```
  ->  Bitmap Heap Scan on reservation o  (estimated rows=12) (actual time=1.109..1.115 rows=14 loops=1)
    Rows Removed by Filter: 2527
    ->  Bitmap Index Scan on idx_res_user_group  (estimated rows=2555) (actual time=0.418..0.418 rows=2541 loops=1)
Execution Time: 1.143 ms
```

**With:** the index finds just the 15 bookings of that week.

```
  ->  Bitmap Heap Scan on reservation o  (estimated rows=12) (actual time=0.011..0.016 rows=14 loops=1)
    ->  Bitmap Index Scan on idx_res_group_start  (estimated rows=13) (actual time=0.009..0.009 rows=15 loops=1)
Execution Time: 0.056 ms
```

The trigger runs once per GPU of every booking while it holds a lock on the group, so a short check also
means less waiting for other users.

## 3. GH001 overlap check, `idx_res_period` and `idx_resgpu_gpu`: 19x faster

```sql
CREATE INDEX idx_res_period ON reservation USING gist (tsrange(start_time, end_time));
CREATE INDEX idx_resgpu_gpu ON reservation_gpu (gpu_id);
```

**Expectation.** The trigger asks whether a GPU has a booking that overlaps a time window. A B-tree cannot
answer "overlaps" (`&&`), because a booking has two ends and there is no single sort order; a GiST index can.

| Median time | Busy week (25 overlapping bookings) | Window without bookings |
|---|---:|---:|
| Both indexes | 0.039 ms | 0.005 ms |
| Without `idx_res_period` | 0.740 ms | 0.852 ms |
| Without `idx_resgpu_gpu` | 0.038 ms | 0.004 ms |
| Without both | 11.4 ms | 11.8 ms |

**With both**, in a busy week:

```
  ->  Bitmap Heap Scan on reservation o  (estimated rows=39) (actual time=0.036..0.039 rows=25 loops=1)
    ->  Bitmap Index Scan on idx_res_period  (estimated rows=42) (actual time=0.031..0.031 rows=27 loops=1)
  ->  Index Only Scan using pk_reservation_gpu on reservation_gpu og  (estimated rows=1) (actual time=0.001..0.001 rows=0 loops=25)
Execution Time: 0.071 ms
```

**Without `idx_res_period`** it has to check all 813 bookings the GPU ever had:

```
    ->  Bitmap Index Scan on idx_resgpu_gpu  (estimated rows=817) (actual time=0.038..0.038 rows=813 loops=1)
  ->  Index Scan using reservation_pkey on reservation o  (estimated rows=1) (actual time=0.001..0.001 rows=0 loops=813)
Execution Time: 1.537 ms
```

**Without both** it reads the whole table:

```
  ->  Seq Scan on reservation o  (estimated rows=881) (actual time=11.539..11.687 rows=25 loops=1)
    Rows Removed by Filter: 95681
Execution Time: 11.719 ms
```

**Findings.** `idx_res_period` does the work: few bookings overlap any given window, but a GPU collects
more and more bookings over the years. `idx_resgpu_gpu` makes no difference **to this query** while the
other index exists. It is still needed: it is the fallback, the foreign key uses it when a GPU is
deleted, and the slot finder reads a GPU's bookings with it.

## 4. Q8 slot search and `idx_res_period`: not used, although it would help

**Expectation.** Q8 checks 168 start times for 44 GPUs, 7,392 overlap tests, each of which the index could answer.

**With the index** (the plan without it is the same): the planner joins *all* bookings by GPU and tests
the overlap afterwards.

```
  ->  Hash Anti Join  (estimated rows=32587) (actual time=60.614..542.298 rows=4602 loops=1)
    Join Filter: (tsrange(r.start_time, r.end_time) && tsrange(s.s, (s.s + '06:00:00'::interval)))
    Rows Removed by Join Filter: 3539893
      ->  Seq Scan on reservation_gpu rg  (estimated rows=216860) (actual time=0.002..6.260 rows=216860 loops=1)
        ->  Seq Scan on reservation r  (estimated rows=88136) (actual time=0.001..7.337 rows=88059 loops=1)
Execution Time: 545.259 ms
```

It compares 3.5 million pairs. Was that the right choice? We forced the alternatives:

| Q8 plan | Median |
|---|---:|
| Chosen by the planner: hash join, no index | 532 ms |
| Forced: look up by GPU with `idx_resgpu_gpu` | 5.9 s |
| Forced: look up by period with `idx_res_period` | 330 ms |

```
  ->  Nested Loop Anti Join  (estimated rows=32587) (actual time=0.407..325.938 rows=4602 loops=1)
    ->  Index Scan using idx_res_period on reservation r  (estimated rows=881) (actual time=0.013..0.021 rows=32 loops=7392)
Execution Time: 330.969 ms
```

**Why.** The planner estimates 881 overlapping bookings per test; in reality there are 32. The time slots
are computed by the query, so it does not know their values and falls back to a default guess of about 1% of
the table. With that estimate the index looks too expensive. So the planner's choice is
1.6 times slower than the best plan we found, and far better than the other alternative.

**What we did about it.** The real cost is the number of tests. The slot finder needs 14 times fewer
(542 instead of 7,392), see the next section.

## 5. Slot finder and `idx_res_period`: 8x faster

The app's version of Q8 tries only the times at which a booking ends (see
[advanced_feature.md](advanced_feature.md)), so it needs 542 overlap tests instead of 7,392. For so few tests
the planner does choose the index.

**Without:**

```
    ->  Bitmap Index Scan on idx_resgpu_gpu  (estimated rows=825) (actual time=0.031..0.031 rows=832 loops=542)
  ->  Index Scan using reservation_pkey on reservation r  (estimated rows=1) (actual time=0.001..0.001 rows=1 loops=449669)
Execution Time: 491.935 ms
```

**With:**

```
    ->  Bitmap Index Scan on idx_res_period  (estimated rows=957) (actual time=0.018..0.018 rows=44 loops=542)
  ->  Index Only Scan using pk_reservation_gpu on reservation_gpu rg  (estimated rows=3) (actual time=0.000..0.000 rows=2 loops=18159)
Execution Time: 67.752 ms
```

**A note on stability.** For another part of the query (finding the ends of bookings) the planner has two
plans of nearly equal cost. Which one it picks depends on the random sample of the last `ANALYZE`: in
eight trials the query took 28 ms twice and 57 to 60 ms six times. Both use `idx_res_period`, so the
speedup is between 8 and 17 times.

**Algorithm and index.** Fewer tests alone hardly help: without the index the slot finder takes 489 ms, against
532 ms for Q8. If both use the index, the algorithm makes the difference: 330 ms for Q8 (forced) against
60.2 ms. Together they are 9 times faster than Q8 as the planner runs it.

## 6. Job reports

### Q7 (weekly model loads) and `idx_job_submit`: slower by default

```sql
CREATE INDEX idx_job_submit ON job (submit_time);
```

**Expectation.** Q7 reads six weeks, 8,747 of 259,203 jobs (3.4%), selected by `submit_time`.

**Without:** three processes read the table, then look up each job's loads by primary key.

```
  Workers Launched: 2
    ->  Parallel Seq Scan on job j  (estimated rows=3584) (actual time=4.893..5.108 rows=2916 loops=3)
    ->  Index Only Scan using pk_job_loads on job_loads_version jl  (estimated rows=1) (actual time=0.001..0.001 rows=1 loops=8747)
Execution Time: 19.179 ms
```

**With:** the index finds the jobs in 1 ms, but the planner then reads *all* 312,946 model loads.

```
  ->  Seq Scan on job_loads_version jl  (estimated rows=312946) (actual time=0.002..10.638 rows=312946 loops=1)
    ->  Index Scan using idx_job_submit on job j  (estimated rows=8601) (actual time=0.018..0.898 rows=8747 loops=1)
Execution Time: 34.478 ms
```

**Why.** The index does its part. The loss comes from the next step, where the planner prefers a full read of
`job_loads_version` to 8,747 index lookups. It assumes that a random page read costs 4 times a
sequential one (`random_page_cost = 4`, meant for spinning disks). Our data is in memory. With the value
usually recommended for SSDs:

| Q7 | Default (4) | `random_page_cost = 1.1` |
|---|---:|---:|
| With `idx_job_submit` | 29.5 ms | 12.3 ms |
| Without | 14.9 ms | 14.3 ms |

```
  ->  Index Scan using idx_job_submit on job j  (estimated rows=8601) (actual time=0.006..0.610 rows=8747 loops=1)
  ->  Index Only Scan using pk_job_loads on job_loads_version jl  (estimated rows=1) (actual time=0.000..0.000 rows=1 loops=8747)
Execution Time: 12.400 ms
```

Then the index wins, by 14% and with one process instead of three. The gain stays small because
reading 29 MB from memory with three processes is fast anyway.

### Q5 (top consumers): no index on `start_time`

Q5 selects jobs by `start_time`, which `idx_job_submit` cannot help with. A what-if index on
`job (start_time)` improves it only from 21.7 ms to 17.8 ms: most of the time is spent on counting the GPUs of
6,350 jobs and on grouping and ranking, not on finding the jobs. Not worth an index.

### Q6 (booked but idle) and `idx_job_reservation`: about 4,400x faster

```sql
CREATE INDEX idx_job_reservation ON job (reservation_id);
```

**Expectation.** Q6 adds up the jobs of each of 2,215 reservations with a correlated subquery. PostgreSQL does
**not** create an index for a foreign key by itself, so without this one every subquery reads all jobs.

**Without** (one run only, because of the duration):

```
  ->  Seq Scan on job j_2  (estimated rows=3) (actual time=15.954..16.167 rows=3 loops=2215)
    Rows Removed by Filter: 259200
Execution Time: 48973.470 ms
```

**With:**

```
  ->  Index Scan using idx_job_reservation on job j_2  (estimated rows=3) (actual time=0.001..0.001 rows=3 loops=2215)
Execution Time: 11.679 ms
```

16 ms per subquery become 0.001 ms. The index also serves the foreign key when a reservation is deleted,
the Jobs screen's filter, and cancelling a booking. It is the most valuable index of the schema.

## 7. A small table: `idx_hosts_version` does not matter

```sql
CREATE INDEX idx_hosts_version ON node_hosts_version (model_id, version_tag);
```

Finding the nodes that host a model version reads `node_hosts_version`, which has 279 rows in 4 pages.

```
Seq Scan on node_hosts_version h  (estimated rows=7) (actual time=0.004..0.017 rows=8 loops=1)
Execution Time: 0.019 ms
```

The planner reads the whole table even though the index exists, because reading 4 pages is as cheap as using
an index. Both variants take about 0.012 ms. We expect the same for
`idx_es_version`, `idx_gpu_type`, `idx_membership_group` and `idx_model_maintainer`, whose tables have 28 to 491 rows,
but did not measure them. They cost 16 kB each and would matter only if these tables grew a lot. (Q8 and the slot
finder do read `idx_hosts_version`, but that step takes about 0.01 ms.)

## 8. Trade-offs: storage and write cost

### Storage

| Table | Size | Its `idx_...` indexes |
|---|---:|---|
| `reservation` | 9768 kB | `idx_res_period` 6760 kB, `idx_res_group_start` 4912 kB, `idx_res_user_group` 880 kB |
| `reservation_gpu` | 7680 kB | `idx_resgpu_gpu` 2096 kB |
| `job` | 29 MB | `idx_job_submit` 5608 kB, `idx_job_reservation` 3776 kB, `idx_job_env` 1680 kB |
| `job_loads_version` | 18 MB | `idx_jl_version` 2128 kB |

For `reservation`, the three indexes (12.3 MB) are larger than the table itself (9.5 MB).

### Write cost

Time to insert 20,000 reservations into a copy of the table with the same rows and indexes (no triggers or foreign keys):

| Indexes on the table | Median | Compared with primary key only |
|---|---:|---:|
| Primary key only | 81 ms | 1.0x |
| All but `idx_res_period` (GiST) | 168 ms | 2.1x |
| All but `idx_res_group_start` | 279 ms | 3.5x |
| All but `idx_res_user_group` | 308 ms | 3.8x |
| All four | 328 ms | 4.1x |

The indexes make an insert about 4 times slower, and the GiST index alone accounts for 65% of the extra
time: GiST pages are more expensive to search and split than B-tree pages. (Over three runs of the script the absolute times varied
by a third, and the GiST share was between 65% and 82%.)

**Is it worth it?** Yes. Per booking the extra cost is about 12 microseconds. `idx_res_period` saves about
701 microseconds in *every* overlap check, which runs at least once per booked GPU, and over 400 ms in
every slot search. Bookings are made by people, so we assume a few per minute at most; for such a load the write cost
does not matter. It would if reservations were loaded in bulk all the time.

## Conclusions

1. **Keep** `idx_job_reservation`, `idx_res_period` and `idx_res_group_start`. They make the booking rules and the
   slot finder fast, and without the first one Q6 is unusable.
2. **Column order decides.** `(group_id, start_time)` serves one group's week but not everyone's week.
3. **An index must fit the operator.** Overlap needs GiST; a B-tree cannot answer it.
4. **Small tables and large fractions do not need indexes.**
5. **The planner can be wrong** when it cannot estimate (Q8) or when its cost settings do not match the hardware
   (Q7).
6. **An algorithm and an index can need each other.** The slot finder's fewer tests hardly help without the index
   (489 ms against 532 ms for Q8), and Q8 does not use the index. Together they take 60.2 ms.
7. `idx_job_submit` is used by Q3 and Q7. With default settings it does not speed up Q7. We keep it, because it wins
   with realistic SSD settings.

We did not change the schema. If the cluster grew, we would first set `random_page_cost = 1.1` (a server setting, not
a schema change) and then consider `reservation (start_time)` for Q2.

**Limits.** Everything was measured with data in memory on one machine and one user at a time. With data on disk, reading whole
tables would cost more and the indexes would help more than shown here.
