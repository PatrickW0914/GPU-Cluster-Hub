package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.ReservationDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Reservation;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import java.time.LocalDateTime;
import java.util.List;

/** GPU reservations. */
public final class ReservationsPanel extends EntityPanel<Reservation> {

    private static final List<Column<Reservation>> COLUMNS = List.of(
            new Column<>("Reservation", Integer.class, Reservation::reservationId),
            new Column<>("User id", Integer.class, Reservation::userId),
            new Column<>("Group id", Integer.class, Reservation::groupId),
            new Column<>("Start", LocalDateTime.class, Reservation::startTime),
            new Column<>("End", LocalDateTime.class, Reservation::endTime),
            new Column<>("Purpose", String.class, Reservation::purpose),
            new Column<>("Status", String.class, Reservation::resStatus),
            new Column<>("Created", LocalDateTime.class, Reservation::createdAt));

    private final FilterBox status = new FilterBox(Allowed.RES_STATUSES);

    public ReservationsPanel() {
        super("Reservation", COLUMNS);
        addFilter("Status", status);
    }

    @Override
    protected Transactions.Work<List<Reservation>> search() {
        String statusValue = status.value();
        return c -> new ReservationDao(c).search(null, null, null, statusValue, null, null);
    }
}
