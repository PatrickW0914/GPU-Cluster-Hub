# Booking transaction: demo script

Shows the required transaction demo on the small dataset (database `gpuhub`), with the
app's clock at the default demo snapshot, **2026-11-02 09:00**. Takes about 4 minutes.

## Before you start

Reload the data so the numbers below match (run from the repo root; this resets `gpuhub`):

```bash
psql -d gpuhub -f sql/01_schema.sql
psql -d gpuhub -f data/load_data.sql        # must end with COMMIT
cd java && mvn -q package && java -jar target/gpuhub.jar
```

In a second terminal, keep this count ready. Run it before and after each step:

```bash
psql -d gpuhub -c "SELECT (SELECT COUNT(*) FROM gpuhub.reservation) AS reservations,
                          (SELECT COUNT(*) FROM gpuhub.reservation_gpu) AS reservation_gpus"
```

The booking screen shows the same two numbers ("Rows in the database").

## 1. A successful 2-GPU booking

Open **GPU Booking** and enter:

| Field | Value |
|---|---|
| User | `tkumar819 - Tomas Kumar` (the group Smart Mobility Lab is filled in) |
| Start / End | `2026-11-03 10:00` / `2026-11-03 14:00` |
| Node | `gpu-a100-02` |
| Purpose | `Demo 1` |

Click **Check availability**, tick GPU **25** and GPU **26**, click **Book selected GPUs**.

- Message: "✓ Reservation N booked: GPU 25, GPU 26."
- Rows: 2367 / 5157 → **2368 / 5159** (one reservation, two GPU rows).
- Quota line: 385.0 → **393.0** of 600.0 (2 GPUs x 4 h).

## 2. The second GPU is already taken: nothing is left behind

Same user, times and node. Purpose `Demo 2`. Tick GPU **27** (free) and GPU **30** (booked).
Click **Book selected GPUs**.

- Dialog: "GPU 30 is already booked during that time." and "The whole booking was rolled back,
  so nothing was saved." (GH001)
- Rows: 2368 / 5159 → **2368 / 5159**, unchanged.
- GPU 27 is **still Free**. Its row was inserted before GPU 30 failed, so this shows the rollback.

Untick both GPUs.

## 3. The booking exceeds the group's weekly quota

| Field | Value |
|---|---|
| User | `lsingh187 - Lucas Singh` (group Computational Genomics Lab) |
| Start / End | `2026-11-03 10:00` / `2026-11-03 18:00` |
| Node | `gpu-a100-01` |
| Purpose | `Demo 3` |

Click **Check availability**: the quota line says 259.0 of 300.0. Tick GPUs **17 to 22**
(6 GPUs x 8 h = 48 GPU-hours; all six are free). Click **Book selected GPUs**.

- Dialog: "This booking would exceed your group's weekly GPU-hour quota." with the detail
  "Group 4 would use 307.0 GPU-hours in week of 2026-11-02, quota is 300.0" (GH003)
- Rows: **2368 / 5159**, unchanged.

Why 307.0? The check runs after each GPU row: five GPUs fit (259 + 40 = 299), and the sixth goes
over (259 + 48 = 307). The five rows that were accepted are rolled back as well.

## 4. Edit and cancel (optional)

Open **Reservations & Jobs**, filter by user `tkumar819` and From `2026-11-03 00:00`, and
select the Demo 1 reservation.

- **Edit**: change the times to `2026-11-03 11:00` / `2026-11-03 15:00` and save.
- **Cancel booking**: confirm. The status becomes CANCELLED, and back in GPU Booking,
  GPUs 25 and 26 are free again.

## What happens in the code

`BookingPanel.book` → `DbTask` (background thread) → `Transactions.run` → `BookingService.book`

1. `Transactions.run` opens a connection and calls `setAutoCommit(false)`.
2. `BookingService.book` locks the chosen GPUs in id order, inserts the reservation
   (`RETURNING reservation_id`), then one `reservation_gpu` row per GPU. A trigger checks
   GH001, GH002 and GH003 on every GPU row.
3. `commit()` checks the deferred rule GH004 (at least one GPU). It is inside the same `try`.
4. Any `SQLException` → `rollback()`, autocommit restored, and `SqlErrors` picks the message.

## Likely questions

- **Why allow ticking a GPU that is already booked?** The list can be out of date: someone else
  may book the GPU a second later. Only the database can decide, so the app lets it.
- **What if two users book the same GPU at the same moment?** The second one waits on the row
  lock, then gets GH001. `BookingTestMain` tries this with eight at once: one wins, seven get GH001.
- **Why lock the GPUs first?** Without it, two such bookings deadlock: each gets a shared lock on the
  GPU row from the foreign key, then waits for the other because the trigger wants an exclusive lock.
  PostgreSQL would still prevent the double booking, but with a deadlock error instead of GH001.
- **Why does GH004 fail at commit?** When the reservation is inserted it has no GPUs yet, so the
  check has to wait until the end of the transaction.
- **Why does cancelling also cancel jobs?** No unfinished job may belong to a cancelled reservation.
  Both updates are in one transaction.

## Automated version

```bash
cd java
java -cp target/gpuhub.jar:target/test-classes edu.ucr.cs166.gpuhub.BookingTestMain   # 19 passed
```

## Clean up

```bash
psql -d gpuhub -c "SET search_path TO gpuhub" -c "DELETE FROM reservation WHERE purpose LIKE 'Demo%'"
```
