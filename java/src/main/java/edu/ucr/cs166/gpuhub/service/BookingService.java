package edu.ucr.cs166.gpuhub.service;

import edu.ucr.cs166.gpuhub.dao.GpuDao;
import edu.ucr.cs166.gpuhub.dao.JobDao;
import edu.ucr.cs166.gpuhub.dao.ReservationDao;
import edu.ucr.cs166.gpuhub.entity.Reservation;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Booking operations that need more than one statement. Call them inside Transactions.run,
 * so that all of their changes are saved or none.
 */
public final class BookingService {

    private BookingService() { }

    /**
     * Books the GPUs and returns the reservation's id: one reservation row, plus one
     * reservation_gpu row per GPU.
     *
     * Each reservation_gpu insert fires a trigger that can refuse the booking: GH001 (GPU already
     * booked), GH002 (GPU not bookable) or GH003 (weekly quota). GH004 (no GPU at all) is a
     * deferred check, so it is raised by the commit. Any of them undoes the whole booking.
     */
    public static int book(Connection c, Reservation reservation, List<Integer> gpuIds) throws SQLException {
        lockGpus(c, gpuIds);
        ReservationDao reservations = new ReservationDao(c);
        int reservationId = reservations.insert(reservation);
        for (int gpuId : gpuIds) {
            reservations.addGpu(reservationId, gpuId);
        }
        return reservationId;
    }

    /**
     * Locks the GPUs until the transaction ends. If two users book the same GPU at the same
     * moment, the second one waits here and is then refused with GH001.
     *
     * Without this, each would get a shared lock on the GPU row (from the foreign key) and then
     * wait for the other one to release it, because the trigger wants an exclusive lock: a
     * deadlock. Always locking in id order avoids a deadlock between two lists of GPUs.
     */
    private static void lockGpus(Connection c, List<Integer> gpuIds) throws SQLException {
        GpuDao gpus = new GpuDao(c);
        for (int gpuId : gpuIds.stream().sorted().toList()) {
            gpus.lock(gpuId);
        }
    }

    /**
     * Changes the times and purpose. Returns false if the reservation no longer exists or is
     * already finished or cancelled. The triggers check the new times again (GH001-GH003).
     */
    public static boolean edit(Connection c, Reservation old, Reservation edited) throws SQLException {
        ReservationDao reservations = new ReservationDao(c);
        boolean sameTimes = old.startTime().equals(edited.startTime()) && old.endTime().equals(edited.endTime());
        if (sameTimes) {
            // Setting the times, even to the same values, would check the booking rules again
            // and could refuse a new purpose because, say, the GPU is in maintenance now.
            return reservations.updatePurpose(edited.reservationId(), edited.purpose());
        }
        lockGpus(c, reservations.gpuIds(edited.reservationId()));
        if (!reservations.updateTimes(edited)) {
            return false;
        }
        // The database checks a job against its reservation when the job changes, but not when
        // the reservation does. So check here that no job is left outside the new times.
        int outside = new JobDao(c).countOutsideReservation(edited.reservationId());
        if (outside > 0) {
            throw new SQLException("Reservation " + edited.reservationId() + " has " + outside
                    + " job(s) that would be outside the new times", "GH005");
        }
        return true;
    }

    /**
     * Cancels the reservation and its unfinished jobs, because no job may run under a cancelled
     * reservation. Returns false if it no longer exists or is already finished or cancelled.
     */
    public static boolean cancel(Connection c, int reservationId, LocalDateTime now) throws SQLException {
        if (!new ReservationDao(c).cancel(reservationId)) {
            return false;
        }
        new JobDao(c).cancelUnfinished(reservationId, now);
        return true;
    }
}
