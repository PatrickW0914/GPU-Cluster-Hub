package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GpuTypeDao;
import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.ModelName;
import edu.ucr.cs166.gpuhub.entity.SlotCandidate;
import edu.ucr.cs166.gpuhub.entity.SlotRequest;
import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.service.SlotFinder;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.DbTask;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.FormDialog;
import edu.ucr.cs166.gpuhub.ui.InputException;
import edu.ucr.cs166.gpuhub.ui.Inputs;
import edu.ucr.cs166.gpuhub.ui.Messages;
import edu.ucr.cs166.gpuhub.ui.ResultsTable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.HierarchyEvent;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/** Slot finder: finds the earliest slots for a model version and books the chosen one. */
public final class SlotFinderPanel extends JPanel {

    private static final List<Column<SlotCandidate>> COLUMNS = List.of(
            new Column<>("Node", String.class, SlotCandidate::hostname),
            new Column<>("Option", Integer.class, SlotCandidate::optionNo),
            new Column<>("Start", LocalDateTime.class, SlotCandidate::start),
            new Column<>("End", LocalDateTime.class, SlotCandidate::end),
            new Column<>("GPUs to book", String.class, SlotFinderPanel::gpuText),
            new Column<>("GPU type", String.class, SlotCandidate::typeName),
            new Column<>("Total VRAM (GB)", Integer.class, SlotCandidate::totalVramGb),
            new Column<>("Needed (GB)", BigDecimal.class, SlotCandidate::minVramGb),
            new Column<>("Free GPUs", Integer.class, SlotCandidate::freeGpus),
            new Column<>("Usable environments", String.class, SlotCandidate::envs));

    private static final ModelName DEFAULT_MODEL = new ModelName("Llama-3.1-8B-Instruct", "bf16");

    private final AppClock clock;
    private final ChoiceBox<ModelName> model = new ChoiceBox<>("Select a model version");
    private final JTextField hours = new JTextField("6", 4);
    private final JTextField days = new JTextField("7", 4);
    private final ChoiceBox<String> minType = new ChoiceBox<>("Any");
    private final JButton findButton = new JButton("Find slots");
    private final JButton bookButton = new JButton("Book this slot");
    private final ResultsTable<SlotCandidate> table = new ResultsTable<>(COLUMNS);
    private final JLabel message = new JLabel(" ");

    private List<Choice<Integer>> userChoices = List.of(); // for the booking dialog
    private Integer lastUserId;                            // suggested in the next booking dialog
    private int latestFind = 0;

    public SlotFinderPanel(AppClock clock) {
        super(new BorderLayout(0, 8));
        this.clock = clock;
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        message.putClientProperty("html.disable", Boolean.TRUE);

        JPanel line1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        addField(line1, "Model version", model);
        addField(line1, "Hours", hours);
        addField(line1, "Search the next (days)", days);
        JPanel line2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        addField(line2, "At least as good as", minType);
        line2.add(findButton);
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(line1);
        top.add(line2);
        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.add(bookButton, BorderLayout.WEST);
        bottom.add(message, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        bookButton.setEnabled(false);
        minType.setToolTipText("Only GPU types with at least this type's VRAM and FP16 speed");
        table.getSelectionModel().addListSelectionListener(e -> bookButton.setEnabled(table.selected() != null));
        findButton.addActionListener(e -> find());
        hours.addActionListener(e -> find()); // Enter
        days.addActionListener(e -> find());
        bookButton.addActionListener(e -> bookSelected());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                loadChoices();
            }
        });
    }

    private static void addField(JPanel panel, String label, JComponent field) {
        panel.add(new JLabel(label));
        panel.add(field);
    }

    private static String gpuText(SlotCandidate slot) {
        return slot.gpuIds().stream().map(id -> "GPU " + id).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------
    // Finding
    // ------------------------------------------------------------------

    private record Choices(List<Choice<ModelName>> models, List<Choice<String>> types,
                           List<Choice<Integer>> users) { }

    private void loadChoices() {
        DbTask.run(c -> new Choices(
                        VersionsPanel.nameChoices(new ModelVersionDao(c)),
                        new GpuTypeDao(c).search(null, null, null, null, null).stream()
                                .map(r -> new Choice<>(r.type().typeName(), r.type().typeName())).toList(),
                        UsersPanel.choices(new UserDao(c), null, true)), // only active users may book
                choices -> {
                    boolean firstTime = model.getItemCount() == 1;
                    model.setChoices(choices.models());
                    if (firstTime) {
                        model.select(DEFAULT_MODEL);
                    }
                    minType.setChoices(choices.types());
                    userChoices = choices.users();
                },
                this::showError);
    }

    private void find() {
        SlotRequest request;
        try {
            request = new SlotRequest(
                    Inputs.requiredChoice("Model version", model),
                    Inputs.requiredInt("Hours", hours, 1, 72), // ck_res_maxlen
                    Inputs.requiredInt("Days", days, 1, 60),
                    minType.value() == null ? "" : minType.value());
        } catch (InputException e) {
            Messages.invalidInput(this, e);
            return;
        }
        LocalDateTime now = clock.now();
        int thisFind = ++latestFind;
        findButton.setEnabled(false);
        showMessage("Searching ...", true);
        DbTask.run(c -> SlotFinder.find(c, request, now),
                slots -> {
                    if (thisFind == latestFind) {
                        findButton.setEnabled(true);
                        showSlots(slots);
                    }
                },
                error -> {
                    if (thisFind == latestFind) {
                        findButton.setEnabled(true);
                        showError(error);
                        Messages.databaseError(this, error);
                    }
                });
    }

    private void showSlots(List<SlotCandidate> slots) {
        table.setRows(slots);
        if (slots.isEmpty()) {
            showMessage("No slot found. Try more days, fewer hours or any GPU type, or check that the version is "
                    + "staged on a running node and verified in an environment.", false);
        } else {
            table.setRowSelectionInterval(0, 0); // the earliest one
            showMessage(slots.size() + " slot(s), the earliest first; up to 3 options per node.", true);
        }
    }

    // ------------------------------------------------------------------
    // Booking
    // ------------------------------------------------------------------

    /** Who books the slot; everything else is already known. */
    private record Booking(int userId, int groupId, String purpose) { }

    private void bookSelected() {
        SlotCandidate slot = table.selected();
        LocalDateTime now = clock.now();
        int[] reservationId = new int[1]; // set by the transaction, shown afterwards
        new FormDialog<>(this, "Book this slot", new BookingForm(slot, model.getSelectedItem().toString()),
                booking -> c -> {
                    reservationId[0] = SlotFinder.book(c, slot, booking.userId(), booking.groupId(),
                            booking.purpose(), now);
                    return true;
                },
                (booking, ok) -> {
                    lastUserId = booking.userId();
                    // The list is not reloaded by itself: it shows the result of the last search.
                    showMessage("✓ Reservation " + reservationId[0] + " booked: " + gpuText(slot) + " on "
                            + slot.hostname() + ". Click Find slots to update the list.", true);
                }).setVisible(true);
    }

    /** The booking dialog: shows the slot and asks who it is for. */
    private final class BookingForm extends EntityForm<Booking> {

        private final ChoiceBox<Integer> user = new ChoiceBox<>("Select a user");
        private final ChoiceBox<Integer> group = new ChoiceBox<>("Select a group");
        private final JTextField purpose = new JTextField(26);

        BookingForm(SlotCandidate slot, String modelName) {
            addField("Node", plainText(slot.hostname() + " (" + slot.typeName() + ")"));
            addField("GPUs", plainText(gpuText(slot)));
            addField("Time", plainText(Inputs.dateTimeText(slot.start()) + " to " + Inputs.dateTimeText(slot.end())));
            addField("User", user);
            addField("Group", group);
            addField("Purpose", purpose);
            user.setChoices(userChoices);
            user.addActionListener(e -> loadGroups());
            purpose.setText("Run " + modelName);
            if (lastUserId != null) {
                user.select(lastUserId); // also loads the groups
            }
        }

        private static JLabel plainText(String text) {
            JLabel label = new JLabel(text);
            label.putClientProperty("html.disable", Boolean.TRUE);
            return label;
        }

        private void loadGroups() {
            Integer userId = user.value();
            if (userId == null) {
                group.setChoices(List.of());
                return;
            }
            DbTask.run(c -> GroupsPanel.choices(new GroupDao(c), userId),
                    groups -> {
                        if (userId.equals(user.value())) { // the user was not changed again meanwhile
                            group.setChoices(groups);
                            if (groups.size() == 1) {
                                group.select(groups.get(0).key());
                            }
                        }
                    },
                    error -> Messages.databaseError(this, error));
        }

        @Override
        protected Booking read() {
            return new Booking(
                    Inputs.requiredChoice("User", user),
                    Inputs.requiredChoice("Group", group),
                    Inputs.requiredText("Purpose", purpose, 200));
        }
    }

    private void showError(SQLException e) {
        showMessage(SqlErrors.message(e), false);
    }

    private void showMessage(String text, boolean good) {
        message.setForeground(good ? Messages.SUCCESS_COLOR : Messages.ERROR_COLOR);
        message.setText(text);
    }
}
