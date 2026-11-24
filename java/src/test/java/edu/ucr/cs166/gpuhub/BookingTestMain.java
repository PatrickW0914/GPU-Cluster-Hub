package edu.ucr.cs166.gpuhub;

import edu.ucr.cs166.gpuhub.dao.CompatibilityDao;
import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.dao.GpuDao;
import edu.ucr.cs166.gpuhub.dao.GpuTypeDao;
import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.JobDao;
import edu.ucr.cs166.gpuhub.dao.ModelDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.dao.ReservationDao;
import edu.ucr.cs166.gpuhub.dao.StagedCopyDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.AiModel;
import edu.ucr.cs166.gpuhub.entity.ClusterUser;
import edu.ucr.cs166.gpuhub.entity.Compatibility;
import edu.ucr.cs166.gpuhub.entity.ComputeNode;
import edu.ucr.cs166.gpuhub.entity.Gpu;
import edu.ucr.cs166.gpuhub.entity.GpuType;
import edu.ucr.cs166.gpuhub.entity.Job;
import edu.ucr.cs166.gpuhub.entity.ModelName;
import edu.ucr.cs166.gpuhub.entity.ModelVersion;
import edu.ucr.cs166.gpuhub.entity.ResearchGroup;
import edu.ucr.cs166.gpuhub.entity.Reservation;
import edu.ucr.cs166.gpuhub.entity.ReservationRow;
import edu.ucr.cs166.gpuhub.entity.SlotCandidate;
import edu.ucr.cs166.gpuhub.entity.SlotRequest;
import edu.ucr.cs166.gpuhub.entity.SoftwareEnv;
import edu.ucr.cs166.gpuhub.entity.StagedCopy;
import edu.ucr.cs166.gpuhub.service.BookingService;
import edu.ucr.cs166.gpuhub.service.GroupService;
import edu.ucr.cs166.gpuhub.service.SlotFinder;

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
            testSlotFinder();
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

    /**
     * The test node has 3 GPUs of 24 GB and the test model needs 40 GB, so 2 GPUs. GPU A is booked
     * 9:00-11:00 and GPU B 9:00-10:30. The earliest 2-hour slot is therefore 10:30 with B and C,
     * which a search that only tries full hours would miss.
     */
    private static void testSlotFinder() throws SQLException {
        LocalDateTime now = LocalDateTime.of(2027, 4, 5, 8, 20);
        LocalDateTime nine = LocalDateTime.of(2027, 4, 5, 9, 0);
        book(nine, nine.plusHours(2), gpuA);
        book(nine, nine.plusMinutes(90), gpuB);
        ModelName model = new ModelName("ZZBOOK-Model", "v1");

        List<SlotCandidate> slots = find(new SlotRequest(model, 2, 7, ""), now);
        SlotCandidate first = slots.get(0);
        check("slot finder: earliest slot is when a booking ends, not only on the hour",
                first.start().equals(nine.plusMinutes(90)) && first.end().equals(nine.plusMinutes(210))
                && first.gpusNeeded() == 2 && first.freeGpus() == 2 && first.totalVramGb() == 48
                && first.gpuIds().equals(List.of(gpuB, gpuC)) && first.envs().equals("zzbook-env"));
        check("slot finder: next option is when GPU A is free too",
                slots.size() == 2 && slots.get(1).start().equals(nine.plusHours(2)) && slots.get(1).freeGpus() == 3);
        check("slot finder: minimum GPU type", find(new SlotRequest(model, 2, 7, "ZZBOOK-GPU"), now).size() == 2
                && find(new SlotRequest(model, 2, 7, "H100-SXM5-80GB"), now).isEmpty());

        check("book the found slot", Transactions.run(c ->
                new ReservationDao(c).gpuIds(SlotFinder.book(c, first, userId, groupId, "zzbook", now)))
                .equals(List.of(gpuB, gpuC)));
        refused("book the same slot again", "GH001",
                () -> Transactions.run(c -> SlotFinder.book(c, first, userId, groupId, "zzbook", now)));
        check("slot finder no longer offers it",
                find(new SlotRequest(model, 2, 7, ""), now).get(0).start().equals(first.end()));

        Transactions.run(c -> new NodeDao(c).update(node("11.8", "UP")));
        check("slot finder: environment needs a newer CUDA than the node supports",
                find(new SlotRequest(model, 2, 7, ""), now).isEmpty());
        Transactions.run(c -> new NodeDao(c).update(node("12.4", "DRAIN")));
        check("slot finder: node is not UP", find(new SlotRequest(model, 2, 7, ""), now).isEmpty());
    }

    private static List<SlotCandidate> find(SlotRequest request, LocalDateTime now) throws SQLException {
        return Transactions.run(c -> SlotFinder.find(c, request, now));
    }

    private static ComputeNode node(String maxCuda, String status) {
        return new ComputeNode(nodeId, "zzbook-node", "gpu", 8, 64, BigDecimal.ONE, new BigDecimal(maxCuda), status, null);
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
            nodeId = new NodeDao(c).insert(node("12.4", "UP"));
            typeId = new GpuTypeDao(c).insert(new GpuType(0, "ZZBOOK-GPU", "NVIDIA", "Test", 24, new BigDecimal("8.6"), BigDecimal.TEN, true));
            envId = new EnvDao(c).insert(new SoftwareEnv(0, "zzbook-env", "3.11", new BigDecimal("12.1"), "PyTorch", "2.4", null, null));
            int modelId = new ModelDao(c).insert(new AiModel(0, "ZZBOOK-Model", "LLM", "PyTorch", "MIT", null, userId, null, null));
            new ModelVersionDao(c).insert(new ModelVersion(modelId, "v1", BigDecimal.TEN, "bf16", new BigDecimal("40.0"), BigDecimal.TEN, null, false));
            new CompatibilityDao(c).insert(new Compatibility(envId, modelId, "v1", LocalDate.of(2026, 1, 1), null));
            new StagedCopyDao(c).insert(new StagedCopy(nodeId, modelId, "v1", "/zzbook", LocalDate.of(2026, 1, 1)));
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
            for (var row : new ModelDao(c).search("ZZBOOK", null, null, null, null, null)) {
                new ModelDao(c).delete(row.model().modelId()); // versions and their records cascade
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
