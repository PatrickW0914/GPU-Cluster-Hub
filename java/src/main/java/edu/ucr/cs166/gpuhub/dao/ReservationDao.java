package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.Reservation;

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
     * Every filter may be null. from/to find the reservations that overlap that time span:
     * two spans overlap if each one starts before the other ends.
     */
    public List<Reservation> search(Integer userId, Integer groupId, Integer gpuId, String status,
                                    LocalDateTime from, LocalDateTime to) throws SQLException {
        Filter f = new Filter()
                .and("r.user_id = ?", userId)
                .and("r.group_id = ?", groupId)
                .and("EXISTS (SELECT 1 FROM reservation_gpu rg"
                        + " WHERE rg.reservation_id = r.reservation_id AND rg.gpu_id = ?)", gpuId)
                .and("r.res_status = ?", status)
                .and("r.end_time > ?", from)
                .and("r.start_time < ?", to);
        return queryAll(SELECT + f.where() + " ORDER BY r.start_time DESC, r.reservation_id",
                ReservationDao::read, f.params());
    }

    /**
     * Updates the times, purpose and status; who booked it and for which group stay fixed.
     * Returns false if the reservation no longer exists.
     */
    public boolean update(Reservation r) throws SQLException {
        return execute("""
                UPDATE reservation
                   SET start_time = ?, end_time = ?, purpose = ?, res_status = ?
                 WHERE reservation_id = ?""",
                r.startTime(), r.endTime(), r.purpose(), r.resStatus(),
                r.reservationId()) == 1;
    }

    /** Deletes the reservation and (by cascade) its GPU rows. Returns false if it no longer exists. */
    public boolean delete(int reservationId) throws SQLException {
        return execute("DELETE FROM reservation WHERE reservation_id = ?", reservationId) == 1;
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
