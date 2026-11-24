package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.Reservation;
import edu.ucr.cs166.gpuhub.entity.ReservationRow;
import edu.ucr.cs166.gpuhub.entity.WeekUsage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** SQL for reservation and reservation_gpu. */
public final class ReservationDao extends Dao {

    private static final String SELECT = """
            SELECT r.reservation_id, r.user_id, r.group_id, r.start_time, r.end_time,
                   r.purpose, r.res_status, r.created_at
              FROM reservation r""";

    /** For lists: adds the names, the GPUs, and the number of unfinished jobs. */
    private static final String SELECT_ROW = """
            SELECT r.reservation_id, r.user_id, r.group_id, r.start_time, r.end_time,
                   r.purpose, r.res_status, r.created_at,
                   u.net_id, g.group_name, v.gpu_count, ROUND(v.gpu_hours, 1) AS gpu_hours,
                   (SELECT STRING_AGG(x.hostname || ': ' || x.gpus, '; ' ORDER BY x.hostname)
                      FROM (SELECT n.hostname, STRING_AGG('GPU ' || p.gpu_id, ', ' ORDER BY p.gpu_id) AS gpus
                              FROM reservation_gpu rg
                              JOIN gpu p          ON p.gpu_id = rg.gpu_id
                              JOIN compute_node n ON n.node_id = p.node_id
                             WHERE rg.reservation_id = r.reservation_id
                             GROUP BY n.hostname) AS x) AS gpus,
                   (SELECT COUNT(*) FROM job j
                     WHERE j.reservation_id = r.reservation_id
                       AND j.job_state IN ('QUEUED', 'RUNNING')) AS open_jobs
              FROM reservation r
              JOIN v_reservation_hours v ON v.reservation_id = r.reservation_id
              JOIN cluster_user u        ON u.user_id = r.user_id
              JOIN research_group g      ON g.group_id = r.group_id""";

    public ReservationDao(Connection c) {
        super(c);
    }

    /**
     * Inserts only the reservation row and returns the generated id. At least one GPU must be
     * added in the same transaction, or the commit fails with GH004.
     *
     * created_at is passed in rather than left to its default, because ck_res_created compares
     * it with start_time: with the demo clock (2026-11-02) the real date would be too late.
     */
    public int insert(Reservation r) throws SQLException {
        return insertReturningId("""
                INSERT INTO reservation (user_id, group_id, start_time, end_time,
                                         purpose, res_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING reservation_id""",
                r.userId(), r.groupId(), r.startTime(), r.endTime(),
                r.purpose(), r.resStatus(), r.createdAt());
    }

    /** Adds one GPU. The triggers check double booking, GPU status and the weekly quota here. */
    public void addGpu(int reservationId, int gpuId) throws SQLException {
        execute("INSERT INTO reservation_gpu (reservation_id, gpu_id) VALUES (?, ?)",
                reservationId, gpuId);
    }

    public List<Integer> gpuIds(int reservationId) throws SQLException {
        return queryAll("SELECT gpu_id FROM reservation_gpu WHERE reservation_id = ? ORDER BY gpu_id",
                rs -> rs.getInt("gpu_id"), reservationId);
    }

    public Optional<Reservation> findById(int reservationId) throws SQLException {
        return queryOne(SELECT + " WHERE r.reservation_id = ?", ReservationDao::read, reservationId);
    }

    /**
     * Every filter may be null. userText is the NetID or name; nodeId finds reservations with
     * a GPU in that node. from/to find the reservations that overlap that time span: two spans
     * overlap if each one starts before the other ends.
     */
    public List<ReservationRow> search(String userText, Integer groupId, Integer nodeId, String status,
                                       LocalDateTime from, LocalDateTime to) throws SQLException {
        Filter f = new Filter()
                .andContains("(u.net_id ILIKE ? OR u.full_name ILIKE ?)", userText)
                .and("r.group_id = ?", groupId)
                .and("""
                        EXISTS (SELECT 1 FROM reservation_gpu rg JOIN gpu p ON p.gpu_id = rg.gpu_id
                                 WHERE rg.reservation_id = r.reservation_id AND p.node_id = ?)""", nodeId)
                .and("r.res_status = ?", status)
                .and("r.end_time > ?", from)
                .and("r.start_time < ?", to);
        return queryAll(SELECT_ROW + f.where() + " ORDER BY r.start_time DESC, r.reservation_id",
                ReservationDao::readRow, f.params());
    }

    // Only reservations that are still open (not completed, not cancelled) can be changed. The
    // condition is part of each UPDATE, so it also holds if someone else changed the reservation
    // in the meantime. Who booked it and for which group never change.

    /** Returns false if the reservation no longer exists or is not open. */
    public boolean updateTimes(Reservation r) throws SQLException {
        return execute("""
                UPDATE reservation SET start_time = ?, end_time = ?, purpose = ?
                 WHERE reservation_id = ? AND res_status IN ('PENDING', 'CONFIRMED', 'ACTIVE')""",
                r.startTime(), r.endTime(), r.purpose(), r.reservationId()) == 1;
    }

    /** Returns false if the reservation no longer exists or is not open. */
    public boolean updatePurpose(int reservationId, String purpose) throws SQLException {
        return execute("""
                UPDATE reservation SET purpose = ?
                 WHERE reservation_id = ? AND res_status IN ('PENDING', 'CONFIRMED', 'ACTIVE')""",
                purpose, reservationId) == 1;
    }

    /** Returns false if the reservation no longer exists or is not open. */
    public boolean cancel(int reservationId) throws SQLException {
        return execute("""
                UPDATE reservation SET res_status = 'CANCELLED'
                 WHERE reservation_id = ? AND res_status IN ('PENDING', 'CONFIRMED', 'ACTIVE')""",
                reservationId) == 1;
    }

    /** The group's booked GPU-hours in the week that contains the given time (the GH003 rule). */
    public Optional<WeekUsage> weekUsage(int groupId, LocalDateTime time) throws SQLException {
        return queryOne("""
                SELECT g.group_name, g.weekly_gpu_hour_quota AS quota,
                       ROUND(COALESCE(SUM(v.gpu_hours), 0), 1) AS booked
                  FROM research_group g
                  LEFT JOIN v_reservation_hours v
                         ON v.group_id = g.group_id AND v.res_status <> 'CANCELLED'
                        AND v.start_time >= date_trunc('week', ?::timestamp)
                        AND v.start_time <  date_trunc('week', ?::timestamp) + INTERVAL '7 days'
                 WHERE g.group_id = ?
                 GROUP BY g.group_id""",
                rs -> new WeekUsage(rs.getString("group_name"), rs.getBigDecimal("booked"),
                        rs.getBigDecimal("quota")),
                time, time, groupId);
    }

    /** Total rows in reservation; the booking screen shows it to prove that a rollback left nothing. */
    public int countReservations() throws SQLException {
        return queryOne("SELECT COUNT(*) FROM reservation", rs -> rs.getInt(1)).orElseThrow();
    }

    /** Total rows in reservation_gpu. */
    public int countReservationGpus() throws SQLException {
        return queryOne("SELECT COUNT(*) FROM reservation_gpu", rs -> rs.getInt(1)).orElseThrow();
    }

    /** Deletes the reservation and (by cascade) its GPU rows. Returns false if it no longer exists. */
    public boolean delete(int reservationId) throws SQLException {
        return execute("DELETE FROM reservation WHERE reservation_id = ?", reservationId) == 1;
    }

    private static ReservationRow readRow(ResultSet rs) throws SQLException {
        return new ReservationRow(read(rs), rs.getString("net_id"), rs.getString("group_name"),
                rs.getInt("gpu_count"), rs.getBigDecimal("gpu_hours"), rs.getString("gpus"),
                rs.getInt("open_jobs"));
    }

    private static Reservation read(ResultSet rs) throws SQLException {
        return new Reservation(
                rs.getInt("reservation_id"), rs.getInt("user_id"), rs.getInt("group_id"),
                rs.getObject("start_time", LocalDateTime.class),
                rs.getObject("end_time", LocalDateTime.class),
                rs.getString("purpose"), rs.getString("res_status"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
