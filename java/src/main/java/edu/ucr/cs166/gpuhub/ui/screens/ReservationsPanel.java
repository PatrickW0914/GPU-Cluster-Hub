package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.dao.ReservationDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.Reservation;
import edu.ucr.cs166.gpuhub.entity.ReservationRow;
import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.service.BookingService;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;
import edu.ucr.cs166.gpuhub.ui.InputException;
import edu.ucr.cs166.gpuhub.ui.Inputs;
import edu.ucr.cs166.gpuhub.ui.Messages;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Reservations: search, change the times, cancel. New ones are made in the GPU Booking section. */
public final class ReservationsPanel extends EntityPanel<ReservationRow> implements Editor<ReservationRow> {

    private static final List<Column<ReservationRow>> COLUMNS = List.of(
            new Column<>("Reservation", Integer.class, r -> r.reservation().reservationId()),
            new Column<>("Status", String.class, r -> r.reservation().resStatus()),
            new Column<>("User", String.class, ReservationRow::netId),
            new Column<>("Group", String.class, ReservationRow::groupName),
            new Column<>("Start", LocalDateTime.class, r -> r.reservation().startTime()),
            new Column<>("End", LocalDateTime.class, r -> r.reservation().endTime()),
            new Column<>("GPUs", Integer.class, ReservationRow::gpuCount),
            new Column<>("GPU-hours", BigDecimal.class, ReservationRow::gpuHours),
            new Column<>("Where", String.class, ReservationRow::gpus),
            new Column<>("Open jobs", Integer.class, ReservationRow::openJobs),
            new Column<>("Purpose", String.class, r -> r.reservation().purpose()));

    private static final List<String> OPEN = List.of("PENDING", "CONFIRMED", "ACTIVE");

    private final AppClock clock;
    private final JTextField user = new JTextField(10);
    private final ChoiceBox<Integer> group = new ChoiceBox<>("Any");
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Any");
    private final FilterBox status = new FilterBox(Allowed.RES_STATUSES);
    private final JTextField from = new JTextField(12);
    private final JTextField to = new JTextField(12);

    public ReservationsPanel(AppClock clock) {
        super("Reservation", COLUMNS);
        this.clock = clock;
        addFilter("User (NetID or name)", user);
        addFilter("Group", group);
        addFilter("Node", node);
        addFilter("Status", status);
        addFilter("From (yyyy-mm-dd hh:mm)", from);
        addFilter("To", to);
        addRowAction("Cancel booking", this::cancelBooking);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> groups = GroupsPanel.choices(new GroupDao(c), null);
            List<Choice<Integer>> nodes = NodesPanel.choices(new NodeDao(c));
            return () -> {
                group.setChoices(groups);
                node.setChoices(nodes);
            };
        };
    }

    @Override
    protected Transactions.Work<List<ReservationRow>> search() {
        String userText = user.getText();
        Integer groupId = group.value();
        Integer nodeId = node.value();
        String statusValue = status.value();
        LocalDateTime fromTime = Inputs.optionalDateTime("From", from);
        LocalDateTime toTime = Inputs.optionalDateTime("To", to);
        if (fromTime != null && toTime != null && !fromTime.isBefore(toTime)) {
            throw new InputException("From must be before To.");
        }
        return c -> new ReservationDao(c).search(userText, groupId, nodeId, statusValue, fromTime, toTime);
    }

    private void cancelBooking(ReservationRow row) {
        Reservation r = row.reservation();
        if (!OPEN.contains(r.resStatus())) {
            Messages.invalidInput(this, new InputException("This booking is already " + r.resStatus() + "."));
            return;
        }
        String warning = row.openJobs() == 0 ? null
                : "Its queued or running jobs (" + row.openJobs() + ") will be cancelled too.";
        if (Messages.confirm(this, "Confirm cancel", "Cancel reservation " + nameOf(row) + "?", warning,
                "Cancel booking", "Keep booking")) {
            LocalDateTime now = clock.now();
            runAction(c -> BookingService.cancel(c, r.reservationId(), now),
                    "Reservation " + nameOf(row) + " cancelled.");
        }
    }

    // ---------------------------------------------------------------- Editor

    @Override
    public boolean canAdd() {
        return false;
    }

    @Override
    public String addHint() {
        return "Book GPUs in the GPU Booking section";
    }

    @Override
    public EntityForm<ReservationRow> form(ReservationRow existing) {
        if (!OPEN.contains(existing.reservation().resStatus())) {
            throw new InputException("Only bookings that are not completed or cancelled can be changed.");
        }
        return new Form(existing);
    }

    @Override
    public Transactions.Work<Boolean> insert(ReservationRow row) {
        throw new UnsupportedOperationException("reservations are added by BookingPanel");
    }

    @Override
    public Transactions.Work<Boolean> update(ReservationRow old, ReservationRow edited) {
        return c -> BookingService.edit(c, old.reservation(), edited.reservation());
    }

    @Override
    public Transactions.Work<Boolean> delete(ReservationRow row) {
        return c -> new ReservationDao(c).delete(row.reservation().reservationId());
    }

    @Override
    public String nameOf(ReservationRow row) {
        return String.valueOf(row.reservation().reservationId());
    }

    @Override
    public String deleteWarning(ReservationRow row) {
        return "This also removes it from the usage reports. To only free its GPUs, cancel the booking instead.";
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "job" -> "Jobs have run in this reservation, and jobs are kept as history. "
                    + "Use Cancel booking instead.";
            default -> null;
        };
    }

    /** Only the times and the purpose can be changed. */
    private static final class Form extends EntityForm<ReservationRow> {

        private final ReservationRow existing;
        private final JTextField start = new JTextField(24);
        private final JTextField end = new JTextField(24);
        private final JTextField purpose = new JTextField(24);

        Form(ReservationRow existing) {
            this.existing = existing;
            addField("Start (yyyy-mm-dd hh:mm)", start);
            addField("End", end);
            addField("Purpose", purpose);
            start.setText(Inputs.dateTimeText(existing.reservation().startTime()));
            end.setText(Inputs.dateTimeText(existing.reservation().endTime()));
            purpose.setText(existing.reservation().purpose());
        }

        @Override
        protected ReservationRow read() {
            LocalDateTime startTime = Inputs.requiredDateTime("Start", start);
            LocalDateTime endTime = Inputs.requiredDateTime("End", end);
            Reservation r = existing.reservation();
            checkWindow(startTime, endTime, r.createdAt());
            Reservation edited = new Reservation(r.reservationId(), r.userId(), r.groupId(), startTime, endTime,
                    Inputs.requiredText("Purpose", purpose, 200), r.resStatus(), r.createdAt());
            return new ReservationRow(edited, existing.netId(), existing.groupName(), existing.gpuCount(),
                    existing.gpuHours(), existing.gpus(), existing.openJobs());
        }
    }

    /** The rules of the CHECK constraints on a reservation's times. */
    static void checkWindow(LocalDateTime start, LocalDateTime end, LocalDateTime createdAt) {
        if (!end.isAfter(start)) {
            throw new InputException(SqlErrors.rule("ck_res_window"));
        }
        if (Duration.between(start, end).compareTo(Duration.ofHours(72)) > 0) {
            throw new InputException(SqlErrors.rule("ck_res_maxlen"));
        }
        if (createdAt.isAfter(start.plusHours(1))) {
            throw new InputException(SqlErrors.rule("ck_res_created"));
        }
    }
}
