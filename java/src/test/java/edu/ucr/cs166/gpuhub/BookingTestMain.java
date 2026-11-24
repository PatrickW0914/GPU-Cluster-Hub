package edu.ucr.cs166.gpuhub;

import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.dao.GpuDao;
import edu.ucr.cs166.gpuhub.dao.GpuTypeDao;
import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.JobDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.dao.ReservationDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.ClusterUser;
import edu.ucr.cs166.gpuhub.entity.ComputeNode;
import edu.ucr.cs166.gpuhub.entity.Gpu;
import edu.ucr.cs166.gpuhub.entity.GpuType;
import edu.ucr.cs166.gpuhub.entity.Job;
import edu.ucr.cs166.gpuhub.entity.Reservation;
import edu.ucr.cs166.gpuhub.entity.ReservationRow;
import edu.ucr.cs166.gpuhub.entity.ResearchGroup;
import edu.ucr.cs166.gpuhub.entity.SoftwareEnv;
import edu.ucr.cs166.gpuhub.service.BookingService;
import edu.ucr.cs166.gpuhub.service.GroupService;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Tests the booking transaction with real commits, on its own user, group, node and GPUs,
 * which are deleted afterwards. Run from java/ after "mvn -q package":
 *
 *   java -cp target/gpuhub.jar:target/test-classes edu.ucr.cs166.gpuhub.BookingTestMain
 */
public final class BookingTestMain {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 9, 0); // the demo clock
    private static final LocalDateTime T10 = LocalDateTime.of(2027, 3, 1, 10, 0); // a Monday

    private static int passed = 0;
    private static int failed = 0;
    private static int userId;
    private static int otherUserId;
    private static int groupId;
    private static int nodeId;
    private static int typeId;
    private static int envId;
    private static int gpuA;
    private static int gpuB;
    private static int gpuC;

    private BookingTestMain() { }

    public static void main(String[] args) throws Exception {
        removeTestRows(); // in case an earlier run was killed halfway
        try {
            createTestRows();
            testBooking();
            testEditing();
            testCancelling();
            testRace();
        } catch (SQLException e) {
            check("unexpected error: " + SqlErrors.message(e) + " " + SqlErrors.detail(e), false);
        } catch (RuntimeException e) {
            check("unexpected error: " + e, false);
        } finally {
            removeTestRows();
        }
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    private static void testBooking() throws SQLException {
        String before = counts();
        int id = book(T10, T10.plusHours(4), gpuA, gpuB);
        check("booking 2 GPUs saves 1 reservation and 2 GPU rows",
                Transactions.run(c -> new ReservationDao(c).gpuIds(id)).equals(List.of(gpuA, gpuB))
                && !before.equals(counts()));

        refused("2nd GPU already booked", "GH001", () -> book(T10.plusHours(1), T10.plusHours(2), gpuC, gpuB));
        refused("quota of 40 GPU-hours exceeded (8 + 33)", "GH003", () -> book(T10.plusHours(4), T10.plusHours(37), gpuA));
        refused("no GPU (checked at commit)", "GH004", () -> book(T10, T10.plusHours(1)));
        refused("longer than 72 hours", "23514", () -> book(T10, T10.plusHours(73), gpuC));
        refused("user who is not in the group", "23503", () -> Transactions.run(c -> BookingService.book(c,
                new Reservation(0, otherUserId, groupId, T10, T10.plusHours(1), "x", "CONFIRMED", NOW), List.of(gpuC))));
        refused("GPU in maintenance", "GH002", () -> {
            setGpuStatus("MAINTENANCE");
            try {
                book(T10, T10.plusHours(1), gpuC);
            } finally {
                setGpuStatus("AVAILABLE");
            }
        });
        book(T10.plusHours(4), T10.plusHours(5), gpuA);
        check("back-to-back booking of the same GPU is allowed", true);
    }

    private static void testEditing() throws SQLException {
        Reservation old = reservation(book(T10.plusDays(1), T10.plusDays(1).plusHours(2), gpuC));
        Reservation moved = withTimes(old, old.startTime().plusHours(1), old.endTime().plusHours(1));
        check("edit times", edit(old, moved) && reservation(old.reservationId()).equals(moved));

        Reservation blocker = reservation(book(T10.plusDays(2), T10.plusDays(2).plusHours(2), gpuC));
        refused("edit onto a booked time", "GH001", () -> edit(moved, withTimes(moved, blocker.startTime(), blocker.endTime())));
        check("refused edit keeps the old times", reservation(old.reservationId()).equals(moved));

        LocalDateTime jobStart = moved.startTime().plusMinutes(30);
        Transactions.run(c -> new JobDao(c).insert(new Job(0, moved.reservationId(), envId, "zzbook-job", NOW,
                jobStart, null, "RUNNING", null, null, null)));
        refused("edit that leaves a job outside", "GH005", () -> edit(moved, withTimes(moved, jobStart.plusMinutes(1), moved.endTime())));
        check("refused edit keeps the old times (job)", reservation(old.reservationId()).equals(moved));

        setGpuStatus("MAINTENANCE");
        Reservation renamed = new Reservation(moved.reservationId(), userId, groupId, moved.startTime(),
                moved.endTime(), "new purpose", moved.resStatus(), moved.createdAt());
        check("purpose can change even if the GPU is in maintenance now", edit(moved, renamed)
                && reservation(old.reservationId()).equals(renamed));
        setGpuStatus("AVAILABLE");
    }

    private static void testCancelling() throws SQLException {
        ReservationRow row = rows("CONFIRMED").stream().filter(r -> r.openJobs() == 1).findFirst().orElseThrow();
        Reservation r = row.reservation();
        LocalDateTime later = r.startTime().plusHours(1);
        check("cancel", Transactions.run(c -> BookingService.cancel(c, r.reservationId(), later))
                && reservation(r.reservationId()).resStatus().equals("CANCELLED"));
        Job job = Transactions.run(c -> new JobDao(c).search("zzbook-job", null, null, null, null, null)).get(0).job();
        check("cancel also cancels its running job and ends it",
                job.jobState().equals("CANCELLED") && later.equals(job.endTime()));
        check("cancelling or editing it again is refused",
                !Transactions.run(c -> BookingService.cancel(c, r.reservationId(), later))
                && !edit(r, withTimes(r, r.startTime().plusHours(1), r.endTime().plusHours(1))));
        book(r.startTime(), r.endTime(), gpuC);
        check("a cancelled booking frees its GPU", true);
    }

    /** Eight users book the same two GPUs (listed in different orders) at the same moment. */
    private static void testRace() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Integer[] gpus = i % 2 == 0 ? new Integer[] {gpuA, gpuB} : new Integer[] {gpuB, gpuA};
            results.add(pool.submit(() -> {
                try {
                    book(T10.plusDays(8), T10.plusDays(8).plusHours(1), gpus);
                    return "booked";
                } catch (SQLException e) {
                    return e.getSQLState();
                }
            }));
        }
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get());
        }
        pool.shutdown();
        check("8 simultaneous bookings: 1 wins, 7 get GH001 " + outcomes,
                outcomes.stream().filter("booked"::equals).count() == 1
                && outcomes.stream().filter("GH001"::equals).count() == threads - 1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private interface Action {
        void run() throws SQLException;
    }

    /** Passes if the action fails with that SQLSTATE and the row counts are the same as before. */
    private static void refused(String name, String sqlState, Action action) throws SQLException {
        String before = counts();
        String got = "no error";
        try {
            action.run();
        } catch (SQLException e) {
            got = e.getSQLState();
        }
        String after = counts();
        check(name + " -> " + got + ", rows " + before + " -> " + after,
                got.equals(sqlState) && before.equals(after));
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    private static String counts() throws SQLException {
        return Transactions.run(c -> new ReservationDao(c).countReservations() + "/"
                + new ReservationDao(c).countReservationGpus());
    }

    private static int book(LocalDateTime start, LocalDateTime end, Integer... gpuIds) throws SQLException {
        return Transactions.run(c -> BookingService.book(c,
                new Reservation(0, userId, groupId, start, end, "zzbook", "CONFIRMED", NOW), List.of(gpuIds)));
    }

    private static boolean edit(Reservation old, Reservation edited) throws SQLException {
        return Transactions.run(c -> BookingService.edit(c, old, edited));
    }

    private static Reservation withTimes(Reservation r, LocalDateTime start, LocalDateTime end) {
        return new Reservation(r.reservationId(), r.userId(), r.groupId(), start, end,
                r.purpose(), r.resStatus(), r.createdAt());
    }

    private static Reservation reservation(int id) throws SQLException {
        return Transactions.run(c -> new ReservationDao(c).findById(id)).orElseThrow();
    }

    private static List<ReservationRow> rows(String status) throws SQLException {
        return Transactions.run(c -> new ReservationDao(c).search("zzbook1", null, null, status, null, null));
    }

    private static void setGpuStatus(String status) throws SQLException {
        Transactions.run(c -> new GpuDao(c).update(
                new Gpu(gpuC, nodeId, 2, typeId, "ZZBOOK2", status, LocalDate.of(2026, 1, 1))));
    }

    private static void createTestRows() throws SQLException {
        Transactions.run(c -> {
            userId = new UserDao(c).insert(new ClusterUser(0, "zzbook1", "Book One", "zzbook1@example.edu", "Faculty", false, true, null));
            otherUserId = new UserDao(c).insert(new ClusterUser(0, "zzbook2", "Book Two", "zzbook2@example.edu", "PhD", false, true, null));
            groupId = GroupService.create(c, new ResearchGroup(0, "ZZ Book Group", "Testing", userId, new BigDecimal("40.0"), null));
            nodeId = new NodeDao(c).insert(new ComputeNode(0, "zzbook-node", "gpu", 8, 64, BigDecimal.ONE, new BigDecimal("12.4"), "UP", null));
            typeId = new GpuTypeDao(c).insert(new GpuType(0, "ZZBOOK-GPU", "NVIDIA", "Test", 24, new BigDecimal("8.6"), BigDecimal.TEN, true));
            envId = new EnvDao(c).insert(new SoftwareEnv(0, "zzbook-env", "3.11", new BigDecimal("12.1"), "PyTorch", "2.4", null, null));
            int[] ids = new int[3];
            for (int slot = 0; slot < 3; slot++) {
                ids[slot] = new GpuDao(c).insert(new Gpu(0, nodeId, slot, typeId, "ZZBOOK" + slot, "AVAILABLE", LocalDate.of(2026, 1, 1)));
            }
            gpuA = ids[0];
            gpuB = ids[1];
            gpuC = ids[2];
            return null;
        });
    }

    private static void removeTestRows() throws SQLException {
        Transactions.run(c -> {
            JobDao jobs = new JobDao(c);
            for (var row : jobs.search("zzbook-job", null, null, null, null, null)) {
                jobs.delete(row.job().jobId());
            }
            ReservationDao reservations = new ReservationDao(c);
            for (var row : reservations.search("zzbook1", null, null, null, null, null)) {
                reservations.delete(row.reservation().reservationId());
            }
            for (var row : new GpuDao(c).search(null, null, null, "ZZBOOK", null, null)) {
                new GpuDao(c).delete(row.gpu().gpuId());
            }
            for (var row : new GpuTypeDao(c).search("ZZBOOK", null, null, null, null)) {
                new GpuTypeDao(c).delete(row.type().gpuTypeId());
            }
            for (var row : new NodeDao(c).search("zzbook", null, null, null, null)) {
                new NodeDao(c).delete(row.node().nodeId());
            }
            for (var row : new EnvDao(c).search("zzbook", null, null, null)) {
                new EnvDao(c).delete(row.env().envId());
            }
            for (var row : new GroupDao(c).search("ZZ Book", null, null, null)) {
                new GroupDao(c).delete(row.group().groupId());
            }
            for (var user : new UserDao(c).search("zzbook", null, null, null)) {
                new UserDao(c).delete(user.userId());
            }
            return null;
        });
    }
}
