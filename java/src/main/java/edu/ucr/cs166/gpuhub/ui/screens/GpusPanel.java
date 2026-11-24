package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GpuDao;
import edu.ucr.cs166.gpuhub.dao.GpuTypeDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.Gpu;
import edu.ucr.cs166.gpuhub.entity.GpuRow;
import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;
import edu.ucr.cs166.gpuhub.ui.Inputs;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Individual GPUs. "Booked now" searches the reservations at the app's current time. */
public final class GpusPanel extends EntityPanel<GpuRow> implements Editor<GpuRow> {

    private static final List<Column<GpuRow>> COLUMNS = List.of(
            new Column<>("GPU", Integer.class, r -> r.gpu().gpuId()),
            new Column<>("Node", String.class, GpuRow::hostname),
            new Column<>("Slot", Integer.class, r -> r.gpu().slotIndex()),
            new Column<>("Type", String.class, GpuRow::typeName),
            new Column<>("VRAM (GB)", Integer.class, GpuRow::vramGb),
            new Column<>("GPU status", String.class, r -> r.gpu().gpuStatus()),
            new Column<>("Node status", String.class, GpuRow::nodeStatus),
            new Column<>("Serial number", String.class, r -> r.gpu().serialNumber()),
            new Column<>("Installed", LocalDate.class, r -> r.gpu().installedOn()));

    private final AppClock clock;
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Any");
    private final ChoiceBox<Integer> type = new ChoiceBox<>("Any");
    private final FilterBox status = new FilterBox(Allowed.GPU_STATUSES);
    private final FilterBox booked = FilterBox.yesNo();
    private final JTextField serial = new JTextField(10);

    private List<Choice<Integer>> nodeChoices = List.of(); // for the form's dropdowns
    private List<Choice<Integer>> typeChoices = List.of();

    public GpusPanel(AppClock clock) {
        super("GPU", COLUMNS);
        this.clock = clock;
        addFilter("Node", node);
        addFilter("Type", type);
        addFilter("GPU status", status);
        addFilter("Booked now", booked);
        addFilter("Serial number", serial);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> nodes = NodesPanel.choices(new NodeDao(c));
            List<Choice<Integer>> types = GpuTypesPanel.choices(new GpuTypeDao(c));
            return () -> {
                nodeChoices = nodes;
                typeChoices = types;
                node.setChoices(nodes);
                type.setChoices(types);
            };
        };
    }

    @Override
    protected Transactions.Work<List<GpuRow>> search() {
        Integer nodeId = node.value();
        Integer typeId = type.value();
        String statusValue = status.value();
        String serialText = serial.getText();
        Boolean bookedValue = booked.booleanValue();
        LocalDateTime now = clock.now();
        return c -> new GpuDao(c).search(nodeId, typeId, statusValue, serialText, bookedValue, now);
    }

    @Override
    public EntityForm<GpuRow> form(GpuRow existing) {
        return new Form(existing, nodeChoices, typeChoices, clock.now().toLocalDate());
    }

    @Override
    public Transactions.Work<Boolean> insert(GpuRow row) {
        return c -> new GpuDao(c).insert(row.gpu()) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(GpuRow old, GpuRow edited) {
        return c -> new GpuDao(c).update(edited.gpu());
    }

    @Override
    public Transactions.Work<Boolean> delete(GpuRow row) {
        return c -> new GpuDao(c).delete(row.gpu().gpuId());
    }

    @Override
    public String nameOf(GpuRow row) {
        return row.gpu().serialNumber();
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "reservation_gpu" -> "This GPU has been booked, and bookings are kept as history. "
                    + "Retire it instead: Edit, then set GPU status to RETIRED.";
            default -> null;
        };
    }

    private static final class Form extends EntityForm<GpuRow> {

        private final int id; // 0 for a new GPU
        private final ChoiceBox<Integer> node = new ChoiceBox<>("Select a node");
        private final JTextField slot = new JTextField(20);
        private final ChoiceBox<Integer> type = new ChoiceBox<>("Select a GPU type");
        private final JTextField serial = new JTextField(20);
        private final JComboBox<String> status = new JComboBox<>(Allowed.GPU_STATUSES.toArray(new String[0]));
        private final JTextField installedOn = new JTextField(20);

        Form(GpuRow existing, List<Choice<Integer>> nodeChoices, List<Choice<Integer>> typeChoices,
             LocalDate today) {
            addField("Node", node);
            addField("Slot (0-15)", slot);
            addField("GPU type", type);
            addField("Serial number", serial);
            addField("GPU status", status);
            addField("Installed on (yyyy-mm-dd)", installedOn);
            node.setChoices(nodeChoices);
            type.setChoices(typeChoices);

            id = existing == null ? 0 : existing.gpu().gpuId();
            if (existing == null) {
                installedOn.setText(today.toString());
            } else {
                Gpu g = existing.gpu();
                node.select(g.nodeId());
                slot.setText(String.valueOf(g.slotIndex()));
                type.select(g.gpuTypeId());
                serial.setText(g.serialNumber());
                status.setSelectedItem(g.gpuStatus());
                installedOn.setText(g.installedOn().toString());
            }
        }

        @Override
        protected GpuRow read() {
            int nodeId = Inputs.requiredChoice("Node", node);
            int slotIndex = Inputs.requiredInt("Slot", slot, 0, 15);
            int typeId = Inputs.requiredChoice("GPU type", type);
            Gpu gpu = new Gpu(id, nodeId, slotIndex, typeId,
                    Inputs.requiredText("Serial number", serial, 30),
                    (String) status.getSelectedItem(),
                    Inputs.requiredDate("Installed on", installedOn));
            // The joined columns are not saved; the list is reloaded afterwards.
            return new GpuRow(gpu, null, null, null, 0);
        }
    }
}
