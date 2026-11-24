package edu.ucr.cs166.gpuhub.service;

import edu.ucr.cs166.gpuhub.dao.SlotDao;
import edu.ucr.cs166.gpuhub.entity.Reservation;
import edu.ucr.cs166.gpuhub.entity.SlotCandidate;
import edu.ucr.cs166.gpuhub.entity.SlotRequest;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** The advanced feature: finds when and where a model version can run, and books it. */
public final class SlotFinder {

    private SlotFinder() { }

    /**
     * Finds slots. Nothing is locked, so a slot can be taken by someone else before it is
     * booked; book() notices that.
     */
    public static List<SlotCandidate> find(Connection c, SlotRequest request, LocalDateTime now)
            throws SQLException {
        return new SlotDao(c).find(Map.of(
                "now", now,
                "model", request.model().modelName(),
                "version", request.model().versionTag(),
                "hours", request.hours(),
                "days", request.days(),
                "min_type", request.minGpuType()));
    }

    /**
     * Books a slot with the normal booking transaction, so all its rules apply, and returns the
     * reservation's id. If the slot was taken in the meantime, it fails with GH001.
     */
    public static int book(Connection c, SlotCandidate slot, int userId, int groupId, String purpose,
                           LocalDateTime now) throws SQLException {
        Reservation reservation = new Reservation(0, userId, groupId, slot.start(), slot.end(),
                purpose, "CONFIRMED", now.truncatedTo(ChronoUnit.SECONDS));
        return BookingService.book(c, reservation, slot.gpuIds());
    }
}
