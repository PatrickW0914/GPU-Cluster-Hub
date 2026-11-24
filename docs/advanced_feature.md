# Advanced feature: GPU slot finder

The slot finder answers "When and where can I run this model, and which GPUs should I book?", and
books the answer with one click. It is in the app under **GPU Booking → Slot finder**.

| Input | Example |
|---|---|
| Model version | `Llama-3.1-70B-Instruct : bf16` |
| Duration in hours (1-72) | 6 |
| How many days to search (1-60) | 7 |
| Minimum GPU type (optional) | `A100-SXM4-80GB` |

The result lists up to three slots per node, the earliest first, each with the exact GPUs to book.
"Book this slot" asks only for the user, group and purpose.

## Why it is useful

Without it, a researcher has to combine five screens by hand: which nodes store the checkpoint,
which environments are verified for it, whether the node's driver supports that environment's CUDA
version, how many GPUs are needed for the model's memory, and when that many GPUs of one node are
free at the same time. For example, the 70B model above needs 160 GB, which is two 80 GB GPUs in the
same node for six hours without interruption. A mistake in any step wastes a booking.

## Why it is technically challenging

1. **It is a search over time, not a lookup.** The database stores when GPUs are *booked*. The finder
   has to derive when they are *free*, for a whole interval, and for several GPUs simultaneously.
2. **There are infinitely many possible start times.** Trying every full hour is slow and misses
   slots: if a booking ends at 10:30, the earliest slot starts at 10:30. (See the argument below.)
3. **Five conditions from ten tables must hold together:** storage, environment, CUDA version,
   memory, and free time.
4. **The result can be outdated a moment later.** Two users may be shown the same slot. Booking must
   never double-book, whatever the list said.

## How it works

The query is [`sql/queries/slot_finder.sql`](../sql/queries/slot_finder.sql). It was developed from
planned query Q8 and is a chain of common table expressions:

| Step | What it computes |
|---|---|
| `target` | The model version and its `min_vram_gb` |
| `candidate` | UP nodes that host it and have a verified environment with `cuda_version <= max_cuda_version`; `gpus_needed = ceil(min_vram_gb / vram_gb)`, per GPU type in the node |
| `wanted` | Keeps GPU types with at least the minimum type's VRAM and FP16 speed |
| `bookable` | Their GPUs with status AVAILABLE |
| `start_times` | The start times worth trying |
| `free` | For every start time, the GPUs without an overlapping booking during the whole slot |
| `ranked` | Slots with enough free GPUs, numbered per node by start time |

### The key idea: only a few start times need to be tried

Take the earliest possible slot and the GPUs it uses. If it started any earlier, at least one of these
GPUs would still be booked, or the time would be before the search begins. So the slot starts either
at the beginning of the search (the next full hour) **or exactly when a booking of one of these GPUs
ends**. Only those times are tried.

| For `Llama-3.1-8B-Instruct : bf16`, 6 hours, 7 days | Q8 (every hour) | Slot finder |
|---|---|---|
| Start times tried per node | 168 | 12 to 19 |
| Median execution time (small dataset, 5 runs) | 18.5 ms | 7.0 ms |
| Median execution time (large dataset, see [indexing_results.md](indexing_results.md)) | 532 ms | 60 ms |
| Finds slots that do not start on the hour | no | yes |

### Differences from Q8

- Exact start times, as explained above.
- GPUs are grouped by type within a node. Q8 counts all GPUs of a node together, which is wrong if a
  node has several types. (The sample data has no such node, so results there are equal.)
- It returns the GPU ids to book and up to three options per node, and supports a minimum GPU type.

### Booking a slot

`SlotFinderPanel` → `SlotFinder.book` → `BookingService.book`. This is the same transaction as on the
booking screen, so every booking rule applies. The finder itself locks nothing: holding locks
while a user looks at a list would block everyone else. Instead, the booking checks again.

## What the database does

**Range overlap.** A booking blocks a slot if `tsrange(start, end) && tsrange(slot_start, slot_end)`.
A `tsrange` includes its start and excludes its end, so a slot may begin exactly when a booking ends.
The GiST index `idx_res_period` on `tsrange(start_time, end_time)` finds overlapping bookings without
reading the whole table; `EXPLAIN` shows the finder using it. A B-tree index cannot answer `&&`.

**Triggers.** Every inserted `reservation_gpu` row fires `check_reservation`, which raises GH001 (GPU
already booked), GH002 (GPU or node not available) or GH003 (weekly quota exceeded). GH004 (a
reservation needs a GPU) is a deferred constraint trigger that runs at commit. So the rules hold for
every program that writes to the database, not only for this app.

**Locking.** The booking transaction first locks the chosen GPU rows (`SELECT ... FOR UPDATE`) in id
order. If two users book the same slot simultaneously, the second one waits until the first commits,
and then gets GH001. Locking in a fixed order prevents two bookings from waiting for each other. The
trigger also locks the group's row, so that two bookings of the same group cannot both use up the
last quota hours.

**Atomicity.** The reservation and all its GPU rows are committed together or rolled back together.

## How it was tested

| Test | Result |
|---|---|
| Earliest slot per node compared with Q8: all 34 model versions, durations of 1, 6, 24 and 72 hours | 136 of 136 equal |
| Earliest slot per node compared with a brute-force search (every 5 minutes) on a copy with 700 extra random bookings at odd minutes, nodes with mixed GPU types, and GPUs in maintenance | 45 of 45 equal; 28 of the 130 slots start off the hour |
| Every suggested set of GPUs is free, on one node, and has enough VRAM | all |
| `BookingTestMain`: start at a booking's end, minimum type, CUDA too old, node not UP, book, book twice | passed |
| Eight simultaneous bookings of the same GPUs | one succeeds, seven get GH001 |

The first three were run once with a separate script; the last two are part of `BookingTestMain`.

## Limits

- It does not check the group's remaining quota, because the group is chosen when booking. A slot that
  exceeds it is refused with GH003 and nothing is saved.
- Among equally good GPUs it suggests those with the lowest ids; it does not try to keep large blocks
  of GPUs together.
- All GPUs of a slot are in one node; jobs that span nodes are not supported.

## Demo

With the demo clock (2026-11-02 09:00) and freshly loaded data:

1. Choose `Llama-3.1-70B-Instruct : bf16`, 6 hours, 7 days, and click **Find slots**. The first row
   is `gpu-a100-02`, 2026-11-02 10:00, GPU 25 and GPU 26 (2 x 80 GB for 160 GB).
2. Click **Book this slot**, choose `tkumar819`, set the purpose to `Demo slot`, and save. The message
   shows the reservation number.
3. The list still shows the old result. Click **Book this slot** again and save: "GPU 25 is already
   booked during that time." (GH001). No rows are left behind.
4. Click **Find slots** again: the first row now suggests GPU 27 and GPU 28.

To clean up, cancel the booking in **Reservations & Jobs**, or run:

```bash
psql -d gpuhub -c "SET search_path TO gpuhub" -c "DELETE FROM reservation WHERE purpose = 'Demo slot'"
```
