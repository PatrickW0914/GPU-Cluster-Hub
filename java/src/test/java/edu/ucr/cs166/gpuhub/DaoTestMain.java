package edu.ucr.cs166.gpuhub;

import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.dao.GpuDao;
import edu.ucr.cs166.gpuhub.dao.GpuTypeDao;
import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.JobDao;
import edu.ucr.cs166.gpuhub.dao.ModelDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.dao.ReservationDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.Database;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.AiModel;
import edu.ucr.cs166.gpuhub.entity.ClusterUser;
import edu.ucr.cs166.gpuhub.entity.ComputeNode;
import edu.ucr.cs166.gpuhub.entity.Gpu;
import edu.ucr.cs166.gpuhub.entity.GpuType;
import edu.ucr.cs166.gpuhub.entity.Job;
import edu.ucr.cs166.gpuhub.entity.Membership;
import edu.ucr.cs166.gpuhub.entity.ModelVersion;
import edu.ucr.cs166.gpuhub.entity.Reservation;
import edu.ucr.cs166.gpuhub.entity.ResearchGroup;
import edu.ucr.cs166.gpuhub.entity.SoftwareEnv;
import edu.ucr.cs166.gpuhub.service.GroupService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tests every DAO against the real database and leaves the data unchanged.
 * Run from java/ after "mvn -q package":
 *
 *   java -cp target/gpuhub.jar:target/test-classes edu.ucr.cs166.gpuhub.DaoTestMain
 *
 * 1. DAO tests: insert, find, search, update and delete one row of every entity, in one
 *    transaction that is always rolled back.
 * 2. GroupService tests: these must really commit, because the group-PI rule is checked at
 *    COMMIT. Their rows are deleted afterwards.
 * 3. Compares the row count of every table before and after.
 */
public final class DaoTestMain {

    private static final List<String> TABLES = List.of(
            "cluster_user", "research_group", "membership", "compute_node", "gpu_type", "gpu",
            "ai_model", "model_version", "software_env", "node_hosts_version",
            "env_supports_version", "reservation", "reservation_gpu", "job", "job_loads_version");

    private static final String TEST_PI = "zztestpi";
    private static final String TEST_GROUP = "ZZ Test Service Group";

    private static int passed = 0;
    private static int failed = 0;

    private DaoTestMain() { }

    public static void main(String[] args) throws SQLException {
        removeServiceTestRows(); // in case an earlier run was killed halfway
        Map<String, Long> before = rowCounts();
        try {
            testDaos();
            testGroupService();
        } catch (SQLException e) {
            check("unexpected error: " + SqlErrors.message(e) + " " + SqlErrors.detail(e), false);
        } catch (RuntimeException e) {
            check("unexpected error: " + e, false);
        } finally {
            removeServiceTestRows();
        }
        check("row counts of all " + TABLES.size() + " tables are unchanged", before.equals(rowCounts()));

        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    // ------------------------------------------------------------------
    // 1. DAO tests
    // ------------------------------------------------------------------

    /**
     * Builds one complete chain (user, group, node, GPU, model, reservation, job ...) so the test
     * does not depend on the sample data, then deletes it in the opposite order.
     */
    private static void testDaos() throws SQLException {
        try (Connection c = Database.open()) {
            c.setAutoCommit(false);
            try {
                runDaoTests(c);
            } finally {
                c.rollback(); // never keep the test rows, whether the tests passed or not
            }
        }
    }

    private static void runDaoTests(Connection c) throws SQLException {
        UserDao users = new UserDao(c);
        GroupDao groups = new GroupDao(c);
        NodeDao nodes = new NodeDao(c);
        GpuTypeDao types = new GpuTypeDao(c);
        GpuDao gpus = new GpuDao(c);
        ModelDao models = new ModelDao(c);
        ModelVersionDao versions = new ModelVersionDao(c);
        EnvDao envs = new EnvDao(c);
        ReservationDao reservations = new ReservationDao(c);
        JobDao jobs = new JobDao(c);

        // An unfiltered search reads every existing row, so it also tests reading real data.
        Map<String, Long> counts = rowCounts();
        check("unfiltered searches return every row",
                users.search(null, null, null, null).size() == counts.get("cluster_user")
                && groups.search(null, null, null).size() == counts.get("research_group")
                && groups.searchMembers(null, null, null).size() == counts.get("membership")
                && nodes.search(null, null, null, null).size() == counts.get("compute_node")
                && types.search(null, null, null, null).size() == counts.get("gpu_type")
                && gpus.search(null, null, null, null).size() == counts.get("gpu")
                && models.search(null, null, null, null).size() == counts.get("ai_model")
                && versions.search(null, null, null, null).size() == counts.get("model_version")
                && envs.search(null, null, null).size() == counts.get("software_env")
                && reservations.search(null, null, null, null, null, null).size() == counts.get("reservation")
                && jobs.search(null, null, null, null, null).size() == counts.get("job"));

        // ---- insert + find, then update + find. Columns filled by the database
        // ---- (created_at ...) are copied from the row that was found.

        int userId = users.insert(new ClusterUser(0, "zztest1", "Test One", "zztest1@example.edu", "Faculty", false, true, null));
        ClusterUser user = users.findById(userId).orElseThrow();
        check("UserDao insert", user.equals(new ClusterUser(userId, "zztest1", "Test One", "zztest1@example.edu", "Faculty", false, true, user.createdAt())));
        user = new ClusterUser(userId, "zztest2", "Test_Two", "zztest2@example.edu", "Faculty", true, false, user.createdAt());
        check("UserDao update", users.update(user) && user.equals(users.findById(userId).orElseThrow()));

        int groupId = groups.insert(new ResearchGroup(0, "ZZ Test Group", "Testing", userId, new BigDecimal("100.0"), null));
        ResearchGroup group = groups.findById(groupId).orElseThrow();
        check("GroupDao insert", group.equals(new ResearchGroup(groupId, "ZZ Test Group", "Testing", userId, new BigDecimal("100.0"), group.createdOn())));
        group = new ResearchGroup(groupId, "ZZ Test Group 2", "Testing 2", userId, new BigDecimal("250.5"), group.createdOn());
        check("GroupDao update", groups.update(group) && group.equals(groups.findById(groupId).orElseThrow()));

        groups.addMember(new Membership(userId, groupId, "Member", null));
        Membership member = groups.findMember(userId, groupId).orElseThrow();
        check("GroupDao membership insert", member.equals(new Membership(userId, groupId, "Member", member.joinedOn())));
        member = new Membership(userId, groupId, "Manager", member.joinedOn());
        check("GroupDao membership update", groups.updateMember(member) && member.equals(groups.findMember(userId, groupId).orElseThrow()));

        int nodeId = nodes.insert(new ComputeNode(0, "zz-test-01", "gpu", 64, 512, new BigDecimal("3.84"), new BigDecimal("12.4"), "UP", "R1"));
        ComputeNode node = new ComputeNode(nodeId, "zz-test-01", "gpu", 64, 512, new BigDecimal("3.84"), new BigDecimal("12.4"), "UP", "R1");
        check("NodeDao insert", node.equals(nodes.findById(nodeId).orElseThrow()));
        node = new ComputeNode(nodeId, "zz-test-02", "debug", 32, 256, new BigDecimal("1.92"), new BigDecimal("12.1"), "UP", null);
        check("NodeDao update", nodes.update(node) && node.equals(nodes.findById(nodeId).orElseThrow()));

        int typeId = types.insert(new GpuType(0, "ZZ-Test-GPU", "NVIDIA", "Test", 24, new BigDecimal("8.6"), new BigDecimal("77.5"), true));
        GpuType type = new GpuType(typeId, "ZZ-Test-GPU", "NVIDIA", "Test", 24, new BigDecimal("8.6"), new BigDecimal("77.5"), true);
        check("GpuTypeDao insert", type.equals(types.findById(typeId).orElseThrow()));
        type = new GpuType(typeId, "ZZ-Test-GPU-2", "AMD", "Test2", 48, new BigDecimal("9.0"), new BigDecimal("100.0"), false);
        check("GpuTypeDao update", types.update(type) && type.equals(types.findById(typeId).orElseThrow()));

        LocalDate installed = LocalDate.of(2026, 1, 15);
        int gpuId = gpus.insert(new Gpu(0, nodeId, 0, typeId, "ZZSERIAL1", "MAINTENANCE", installed));
        Gpu gpu = new Gpu(gpuId, nodeId, 0, typeId, "ZZSERIAL1", "MAINTENANCE", installed);
        check("GpuDao insert", gpu.equals(gpus.findById(gpuId).orElseThrow()));
        gpu = new Gpu(gpuId, nodeId, 3, typeId, "ZZSERIAL2", "AVAILABLE", installed.plusDays(1));
        check("GpuDao update", gpus.update(gpu) && gpu.equals(gpus.findById(gpuId).orElseThrow()));

        int modelId = models.insert(new AiModel(0, "ZZ-Test-Model", "LLM", "PyTorch", "MIT", "https://example.edu/m", userId, "first", null));
        AiModel model = models.findById(modelId).orElseThrow();
        check("ModelDao insert", model.equals(new AiModel(modelId, "ZZ-Test-Model", "LLM", "PyTorch", "MIT", "https://example.edu/m", userId, "first", model.addedOn())));
        model = new AiModel(modelId, "ZZ-Test-Model-2", "Vision", "JAX", "Apache-2.0", null, userId, null, model.addedOn());
        check("ModelDao update", models.update(model) && model.equals(models.findById(modelId).orElseThrow()));

        ModelVersion version = new ModelVersion(modelId, "v1", new BigDecimal("7.000"), "bf16", new BigDecimal("16.0"), new BigDecimal("14.50"), null, false);
        versions.insert(version);
        check("ModelVersionDao insert", version.equals(versions.findById(modelId, "v1").orElseThrow()));
        version = new ModelVersion(modelId, "v1-renamed", new BigDecimal("7.500"), "int8", new BigDecimal("9.0"), new BigDecimal("8.25"), installed, true);
        check("ModelVersionDao update (also renames the tag)", versions.update("v1", version)
                && version.equals(versions.findById(modelId, "v1-renamed").orElseThrow())
                && versions.findById(modelId, "v1").isEmpty());

        int envId = envs.insert(new SoftwareEnv(0, "zz-test-env", "3.11", new BigDecimal("12.1"), "PyTorch", "2.4", "img/zz:1", null));
        SoftwareEnv env = envs.findById(envId).orElseThrow();
        check("EnvDao insert", env.equals(new SoftwareEnv(envId, "zz-test-env", "3.11", new BigDecimal("12.1"), "PyTorch", "2.4", "img/zz:1", env.createdOn())));
        env = new SoftwareEnv(envId, "zz-test-env-2", "3.12", new BigDecimal("12.4"), "JAX", "0.4", null, env.createdOn());
        check("EnvDao update", envs.update(env) && env.equals(envs.findById(envId).orElseThrow()));

        LocalDateTime now = LocalDateTime.of(2026, 11, 2, 9, 0); // the demo clock
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 10, 0);
        LocalDateTime end = start.plusHours(4);
        int resId = reservations.insert(new Reservation(0, userId, groupId, start, end, "test", "PENDING", now));
        reservations.addGpu(resId, gpuId);
        Reservation res = new Reservation(resId, userId, groupId, start, end, "test", "PENDING", now);
        check("ReservationDao insert + addGpu", res.equals(reservations.findById(resId).orElseThrow())
                && reservations.gpuIds(resId).equals(List.of(gpuId)));
        res = new Reservation(resId, userId, groupId, start.plusHours(1), end.plusHours(1), "moved", "CONFIRMED", now);
        check("ReservationDao update", reservations.update(res) && res.equals(reservations.findById(resId).orElseThrow()));

        long jobId = jobs.insert(new Job(0, resId, envId, "zz-job", start, null, null, "QUEUED", null, null, null));
        Job job = new Job(jobId, resId, envId, "zz-job", start, null, null, "QUEUED", null, null, null);
        check("JobDao insert", job.equals(jobs.findById(jobId).orElseThrow()));
        job = new Job(jobId, resId, envId, "zz-job-2", start, start.plusHours(1), start.plusHours(2), "COMPLETED", 0, new BigDecimal("12.5"), new BigDecimal("88.25"));
        check("JobDao update", jobs.update(job) && job.equals(jobs.findById(jobId).orElseThrow()));

        // Check the deferred rules now, as COMMIT would: the chain must satisfy them.
        setConstraints(c, "IMMEDIATE");
        setConstraints(c, "DEFERRED");
        check("deferred rules hold for the whole chain", true);

        // ---- search with every filter set: finds exactly the test row
        check("UserDao search", users.search("ZZTEST", "Faculty", groupId, false).equals(List.of(user)));
        check("UserDao search treats _ literally", users.search("test_two", null, null, null).equals(List.of(user))
                && users.search("t_st_two", null, null, null).isEmpty()); // would match if _ were a wildcard
        check("UserDao search binds quotes safely", users.search("' OR '1'='1", null, null, null).isEmpty());
        check("GroupDao search", groups.search("zz test", "testing", userId).equals(List.of(group)));
        check("GroupDao membership search", groups.searchMembers(userId, groupId, "Manager").equals(List.of(member)));
        check("NodeDao search", nodes.search("zz-", "debug", "UP", typeId).equals(List.of(node)));
        check("GpuTypeDao search", types.search("zz-", "AMD", 48, false).equals(List.of(type)));
        check("GpuDao search", gpus.search(nodeId, typeId, "AVAILABLE", "zzserial").equals(List.of(gpu)));
        check("ModelDao search", models.search("zz-", "Vision", "JAX", userId).equals(List.of(model)));
        check("ModelVersionDao search", versions.search(modelId, "int8", new BigDecimal("9"), true).equals(List.of(version))
                && versions.search(modelId, null, new BigDecimal("8.9"), null).isEmpty());
        check("EnvDao search", envs.search("zz-", "JAX", new BigDecimal("12.4")).equals(List.of(env)));
        // The reservation is 11:00-15:00. One that only touches its end does not overlap it.
        check("ReservationDao search", reservations.search(userId, groupId, gpuId, "CONFIRMED", start.plusHours(4), start.plusHours(9)).equals(List.of(res))
                && reservations.search(userId, null, null, null, start.plusHours(5), null).isEmpty()
                && reservations.search(userId, null, null, null, null, start.plusHours(1)).isEmpty());
        check("JobDao search", jobs.search("zz-", "COMPLETED", resId, userId, envId).equals(List.of(job)));

        // ---- delete, dependents first
        check("JobDao delete", jobs.delete(jobId) && jobs.findById(jobId).isEmpty());
        check("ReservationDao delete (GPU rows cascade)", reservations.delete(resId) && reservations.findById(resId).isEmpty()
                && reservations.gpuIds(resId).isEmpty());
        check("EnvDao delete", envs.delete(envId) && envs.findById(envId).isEmpty());
        check("ModelVersionDao delete", versions.delete(modelId, "v1-renamed") && versions.findById(modelId, "v1-renamed").isEmpty());
        check("ModelDao delete", models.delete(modelId) && models.findById(modelId).isEmpty());
        check("GpuDao delete", gpus.delete(gpuId) && gpus.findById(gpuId).isEmpty());
        check("GpuTypeDao delete", types.delete(typeId) && types.findById(typeId).isEmpty());
        check("NodeDao delete", nodes.delete(nodeId) && nodes.findById(nodeId).isEmpty());

        int memberId = users.insert(new ClusterUser(0, "zztest3", "Test Three", "zztest3@example.edu", "MS", false, true, null));
        groups.addMember(new Membership(memberId, groupId, "Member", null));
        check("GroupDao membership delete", groups.removeMember(memberId, groupId) && groups.findMember(memberId, groupId).isEmpty());
        check("GroupDao delete (memberships cascade)", groups.delete(groupId) && groups.findById(groupId).isEmpty()
                && groups.findMember(userId, groupId).isEmpty());
        check("UserDao delete", users.delete(userId) && users.findById(userId).isEmpty());
        check("update/delete of a missing row returns false", !users.update(user) && !users.delete(userId));
        setConstraints(c, "IMMEDIATE");
        check("deferred rules hold after the deletes", true);
    }

    private static void setConstraints(Connection c, String mode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SET CONSTRAINTS ALL " + mode)) { // fixed text, not input
            ps.execute();
        }
    }

    // ------------------------------------------------------------------
    // 2. GroupService tests (these commit)
    // ------------------------------------------------------------------

    private static void testGroupService() throws SQLException {
        int piId = Transactions.run(c -> new UserDao(c).insert(
                new ClusterUser(0, TEST_PI, "Test PI", TEST_PI + "@example.edu", "Faculty", false, true, null)));
        ResearchGroup newGroup = new ResearchGroup(0, TEST_GROUP, "Testing", piId, new BigDecimal("50.0"), null);

        // The group alone is accepted by the INSERT and rejected by the commit.
        expectError("group without its PI membership fails at commit, nothing kept", "23503",
                () -> Transactions.run(c -> new GroupDao(c).insert(newGroup)));

        // The second statement fails, so the first one must disappear too.
        expectError("bad membership undoes the group insert", "23514",
                () -> Transactions.run(c -> {
                    GroupDao groups = new GroupDao(c);
                    int id = groups.insert(newGroup);
                    groups.addMember(new Membership(piId, id, "NotARole", null));
                    return id;
                }));

        try (Connection c = Database.open()) {
            UserDao users = new UserDao(c);
            ClusterUser pi = users.findById(piId).orElseThrow();
            users.update(new ClusterUser(piId, pi.netId(), pi.fullName(), pi.email(), "PhD", false, true, pi.createdAt()));
            expectError("PI who is not Faculty is rejected", "GH008", () -> GroupService.create(newGroup));
            users.update(pi);
        }

        int groupId = GroupService.create(newGroup);
        try (Connection c = Database.open()) {
            GroupDao groups = new GroupDao(c);
            check("GroupService.create saves the group and the PI membership",
                    groups.findById(groupId).orElseThrow().piUserId() == piId
                    && groups.findMember(piId, groupId).orElseThrow().memberRole().equals("Manager"));
        }
    }

    private interface Action {
        void run() throws SQLException;
    }

    /** Passes if the action fails with that SQLSTATE and no test group was left behind. */
    private static void expectError(String name, String sqlState, Action action) throws SQLException {
        String got = "no error";
        try {
            action.run();
        } catch (SQLException e) {
            got = e.getSQLState();
        }
        try (Connection c = Database.open()) {
            boolean nothingKept = new GroupDao(c).search(TEST_GROUP, null, null).isEmpty();
            check(name + " (" + got + ")", got.equals(sqlState) && nothingKept);
        }
    }

    private static void removeServiceTestRows() throws SQLException {
        Transactions.run(c -> {
            GroupDao groups = new GroupDao(c);
            for (ResearchGroup g : groups.search(TEST_GROUP, null, null)) {
                groups.delete(g.groupId());
            }
            UserDao users = new UserDao(c);
            for (ClusterUser u : users.search(TEST_PI, null, null, null)) {
                users.delete(u.userId());
            }
            return null;
        });
    }

    // ------------------------------------------------------------------
    // 3. Row counts
    // ------------------------------------------------------------------

    private static Map<String, Long> rowCounts() throws SQLException {
        Map<String, Long> counts = new TreeMap<>();
        try (Connection c = Database.open()) {
            for (String table : TABLES) { // fixed names from the list above, not input
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    counts.put(table, rs.getLong(1));
                }
            }
        }
        return counts;
    }
}
