package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GpuDao;
import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.dao.ReservationDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.GpuSlot;
import edu.ucr.cs166.gpuhub.entity.Reservation;
import edu.ucr.cs166.gpuhub.entity.WeekUsage;
import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.service.BookingService;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.DbTask;
import edu.ucr.cs166.gpuhub.ui.InputException;
import edu.ucr.cs166.gpuhub.ui.Inputs;
import edu.ucr.cs166.gpuhub.ui.Messages;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.Collectors;

/** Books one or more GPUs of a node as one transaction. */
public final class BookingPanel extends JPanel {

    private final AppClock clock;
    private final ChoiceBox<Integer> user = new ChoiceBox<>("Select a user");
    private final ChoiceBox<Integer> group = new ChoiceBox<>("Select a group");
    private final JTextField start = new JTextField(14);
    private final JTextField end = new JTextField(14);
    private final JTextField purpose = new JTextField(30);
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Select a node");
    private final JButton checkButton = new JButton("Check availability");
    private final GpuPickTable gpus = new GpuPickTable(this::showPicked);
    private final JLabel picked = plainLabel();
    private final JLabel usage = plainLabel();
    private final JLabel counts = plainLabel();
    private final JLabel message = plainLabel();
    private final JButton bookButton = new JButton("Book selected GPUs");

    private boolean fillingDropdowns = false; // true while the program, not the user, changes them
    private int latestRefresh = 0;

    public BookingPanel(AppClock clock) {
        super(new BorderLayout(0, 8));
        this.clock = clock;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        add(inputs(), BorderLayout.NORTH);
        add(new JScrollPane(gpus), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        user.addActionListener(e -> {
            if (!fillingDropdowns) {
                loadGroups();
            }
        });
        group.addActionListener(e -> refresh(false));
        node.addActionListener(e -> refresh(false));
        start.addActionListener(e -> refresh(true)); // Enter
        end.addActionListener(e -> refresh(true));
        checkButton.addActionListener(e -> refresh(true));
        bookButton.addActionListener(e -> book());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                suggestTimes();
                loadChoices();
            }
        });
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private JPanel inputs() {
        JPanel panel = new JPanel(new GridBagLayout());
        addField(panel, 0, 0, "User", user);
        addField(panel, 0, 2, "Group", group);
        addField(panel, 1, 0, "Start (yyyy-mm-dd hh:mm)", start);
        addField(panel, 1, 2, "End", end);
        addField(panel, 2, 0, "Node", node);
        addField(panel, 2, 2, "Purpose", purpose);
        addField(panel, 3, 2, "", checkButton);
        return panel;
    }

    private static void addField(JPanel panel, int row, int column, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = column;
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.LINE_END;
        panel.add(new JLabel(label), c);
        c.gridx = column + 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = field instanceof JButton ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    private JPanel footer() {
        JPanel lines = new JPanel(new GridLayout(0, 1, 0, 2));
        lines.add(picked);
        lines.add(usage);
        lines.add(counts);
        lines.add(message);
        JPanel button = new JPanel(new BorderLayout());
        button.add(bookButton, BorderLayout.SOUTH);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(lines, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    private static JLabel plainLabel() {
        JLabel label = new JLabel(" ");
        label.putClientProperty("html.disable", Boolean.TRUE); // it can contain user data
        return label;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private record Choices(List<Choice<Integer>> users, List<Choice<Integer>> nodes) { }

    private record Availability(List<GpuSlot> slots, WeekUsage usage, int reservations, int reservationGpus) { }

    /** Suggests the next full hour, unless the user already typed a time that can still be booked. */
    private void suggestTimes() {
        try {
            ReservationsPanel.checkWindow(Inputs.requiredDateTime("Start", start),
                    Inputs.requiredDateTime("End", end), clock.now());
        } catch (InputException e) {
            LocalDateTime nextHour = clock.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
            start.setText(Inputs.dateTimeText(nextHour));
            end.setText(Inputs.dateTimeText(nextHour.plusHours(4)));
        }
    }

    /** Only active users can be chosen; the database itself does not check that. */
    private void loadChoices() {
        DbTask.run(c -> new Choices(UsersPanel.choices(new UserDao(c), null, true),
                        NodesPanel.choices(new NodeDao(c))),
                choices -> {
                    fillingDropdowns = true;
                    user.setChoices(choices.users());
                    node.setChoices(choices.nodes());
                    fillingDropdowns = false;
                    loadGroups();
                },
                this::showError);
    }

    /** Offers only the groups of the chosen user, because a user can only book for their own group. */
    private void loadGroups() {
        Integer userId = user.value();
        if (userId == null) {
            setGroups(List.of());
            return;
        }
        DbTask.run(c -> GroupsPanel.choices(new GroupDao(c), userId),
                groups -> {
                    if (userId.equals(user.value())) { // the user was not changed again meanwhile
                        setGroups(groups);
                    }
                },
                this::showError);
    }

    private void setGroups(List<Choice<Integer>> groups) {
        fillingDropdowns = true;
        group.setChoices(groups);
        if (groups.size() == 1) {
            group.select(groups.get(0).key());
        }
        fillingDropdowns = false;
        refresh(false);
    }

    /**
     * Loads which GPUs of the node are free in the time window, and the group's quota usage.
     *
     * @param asked whether the user asked for it; then invalid input is shown in a dialog
     */
    private void refresh(boolean asked) {
        if (fillingDropdowns) {
            return;
        }
        Integer nodeId = node.value();
        Integer groupId = group.value();
        LocalDateTime from;
        LocalDateTime to;
        try {
            from = Inputs.requiredDateTime("Start", start);
            to = Inputs.requiredDateTime("End", end);
            ReservationsPanel.checkWindow(from, to, clock.now());
            if (asked) {
                Inputs.requiredChoice("Node", node);
            }
        } catch (InputException e) {
            if (asked) {
                Messages.invalidInput(this, e);
            } else {
                showMessage(e.getMessage(), false);
            }
            return;
        }
        int thisRefresh = ++latestRefresh;
        DbTask.run(c -> {
                    ReservationDao reservations = new ReservationDao(c);
                    return new Availability(
                            nodeId == null ? List.of() : new GpuDao(c).slots(nodeId, from, to),
                            groupId == null ? null : reservations.weekUsage(groupId, from).orElse(null),
                            reservations.countReservations(), reservations.countReservationGpus());
                },
                a -> {
                    if (thisRefresh == latestRefresh) { // ignore the answer to an older question
                        showAvailability(a, from);
                    }
                },
                this::showError);
    }

    private void showAvailability(Availability a, LocalDateTime from) {
        gpus.setSlots(a.slots());
        WeekUsage u = a.usage();
        // Quota weeks start on Monday, like date_trunc('week') in the database.
        LocalDate monday = from.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        usage.setText(u == null ? " " : "Quota: " + u.groupName() + " has booked " + u.booked() + " of "
                + u.quota() + " GPU-hours in the week of " + monday);
        counts.setText("Rows in the database: " + a.reservations() + " in reservation, "
                + a.reservationGpus() + " in reservation_gpu");
        showPicked();
    }

    /** Summarizes the ticked GPUs. */
    private void showPicked() {
        List<GpuSlot> slots = gpus.picked();
        int vram = slots.stream().mapToInt(s -> s.row().vramGb()).sum();
        String text = "Selected: " + slots.size() + " GPU(s), " + vram + " GB VRAM";
        try {
            long minutes = Duration.between(Inputs.requiredDateTime("Start", start),
                    Inputs.requiredDateTime("End", end)).toMinutes();
            BigDecimal gpuHours = BigDecimal.valueOf(minutes * slots.size())
                    .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
            text += ", " + gpuHours + " GPU-hours";
        } catch (InputException e) {
            // the times are not valid yet, so there is no number of hours to show
        }
        picked.setText(text);
    }

    // ------------------------------------------------------------------
    // Booking
    // ------------------------------------------------------------------

    private void book() {
        Reservation reservation;
        List<Integer> gpuIds;
        try {
            int userId = Inputs.requiredChoice("User", user);
            int groupId = Inputs.requiredChoice("Group", group);
            LocalDateTime from = Inputs.requiredDateTime("Start", start);
            LocalDateTime to = Inputs.requiredDateTime("End", end);
            LocalDateTime now = clock.now().truncatedTo(ChronoUnit.SECONDS);
            ReservationsPanel.checkWindow(from, to, now);
            Inputs.requiredChoice("Node", node);
            String purposeText = Inputs.requiredText("Purpose", purpose, 200);
            gpuIds = gpus.picked().stream().map(s -> s.row().gpu().gpuId()).toList();
            if (gpuIds.isEmpty()) {
                throw new InputException("Select at least one GPU.");
            }
            reservation = new Reservation(0, userId, groupId, from, to, purposeText, "CONFIRMED", now);
        } catch (InputException e) {
            Messages.invalidInput(this, e);
            return;
        }
        String gpuText = gpuIds.stream().map(id -> "GPU " + id).collect(Collectors.joining(", "));
        setBusy(true);
        showMessage(" ", true);
        DbTask.run(c -> BookingService.book(c, reservation, gpuIds), // one transaction
                id -> {
                    setBusy(false);
                    gpus.clearPicks();
                    showMessage("✓ Reservation " + id + " booked: " + gpuText + ".", true);
                    refresh(false);
                },
                error -> {
                    setBusy(false);
                    refresh(false); // the row counts show that nothing was left behind
                    showMessage("Nothing was booked: " + SqlErrors.message(error), false);
                    Messages.databaseError(this, error,
                            "The whole booking was rolled back, so nothing was saved.");
                });
    }

    private void setBusy(boolean busy) {
        bookButton.setEnabled(!busy);
        checkButton.setEnabled(!busy);
    }

    private void showError(SQLException e) {
        showMessage(SqlErrors.message(e), false);
    }

    private void showMessage(String text, boolean good) {
        message.setForeground(good ? Messages.SUCCESS_COLOR : Messages.ERROR_COLOR);
        message.setText(text);
    }
}
