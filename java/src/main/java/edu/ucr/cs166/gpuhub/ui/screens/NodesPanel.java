package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GpuTypeDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.ComputeNode;
import edu.ucr.cs166.gpuhub.entity.NodeRow;
import edu.ucr.cs166.gpuhub.entity.VersionKey;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;
import edu.ucr.cs166.gpuhub.ui.Inputs;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

/** Compute nodes. Two filters search related tables: GPUs, and staged checkpoints. */
public final class NodesPanel extends EntityPanel<NodeRow> implements Editor<NodeRow> {

    private static final List<Column<NodeRow>> COLUMNS = List.of(
            new Column<>("Hostname", String.class, r -> r.node().hostname()),
            new Column<>("Status", String.class, r -> r.node().nodeStatus()),
            new Column<>("Partition", String.class, r -> r.node().partitionName()),
            new Column<>("GPUs", Integer.class, NodeRow::gpuCount),
            new Column<>("GPU types", String.class, NodeRow::gpuTypes),
            new Column<>("CPU cores", Integer.class, r -> r.node().cpuCores()),
            new Column<>("RAM (GB)", Integer.class, r -> r.node().ramGb()),
            new Column<>("NVMe (TB)", BigDecimal.class, r -> r.node().localNvmeTb()),
            new Column<>("Staged (GB)", BigDecimal.class, NodeRow::stagedGb),
            new Column<>("Max CUDA", BigDecimal.class, r -> r.node().maxCudaVersion()),
            new Column<>("Rack", String.class, r -> r.node().rackLocation()));

    private final JTextField hostname = new JTextField(12);
    private final FilterBox partition = new FilterBox(Allowed.PARTITIONS);
    private final FilterBox status = new FilterBox(Allowed.NODE_STATUSES);
    private final ChoiceBox<Integer> gpuType = new ChoiceBox<>("Any");
    private final ChoiceBox<VersionKey> hosted = new ChoiceBox<>("Any");

    public NodesPanel() {
        super("Node", COLUMNS);
        addFilter("Hostname", hostname);
        addFilter("Partition", partition);
        addFilter("Status", status);
        addFilter("Has GPU type", gpuType);
        addFilter("Hosts model version", hosted);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> types = GpuTypesPanel.choices(new GpuTypeDao(c));
            List<Choice<VersionKey>> versions = VersionsPanel.choices(new ModelVersionDao(c));
            return () -> {
                gpuType.setChoices(types);
                hosted.setChoices(versions);
            };
        };
    }

    @Override
    protected Transactions.Work<List<NodeRow>> search() {
        String hostnameText = hostname.getText();
        String partitionValue = partition.value();
        String statusValue = status.value();
        Integer gpuTypeId = gpuType.value();
        VersionKey hostedVersion = hosted.value();
        return c -> new NodeDao(c).search(hostnameText, partitionValue, statusValue, gpuTypeId, hostedVersion);
    }

    /** All nodes as dropdown choices. */
    static List<Choice<Integer>> choices(NodeDao nodes) throws SQLException {
        return nodes.search(null, null, null, null, null).stream()
                .map(r -> new Choice<>(r.node().nodeId(),
                        r.node().hostname() + (r.node().nodeStatus().equals("UP")
                                ? "" : " (" + r.node().nodeStatus() + ")")))
                .toList();
    }

    @Override
    public EntityForm<NodeRow> form(NodeRow existing) {
        return new Form(existing);
    }

    @Override
    public Transactions.Work<Boolean> insert(NodeRow row) {
        return c -> new NodeDao(c).insert(row.node()) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(NodeRow old, NodeRow edited) {
        return c -> new NodeDao(c).update(edited.node());
    }

    @Override
    public Transactions.Work<Boolean> delete(NodeRow row) {
        return c -> new NodeDao(c).delete(row.node().nodeId());
    }

    @Override
    public String nameOf(NodeRow row) {
        return row.node().hostname();
    }

    @Override
    public String deleteWarning(NodeRow row) {
        return row.stagedCount() == 0 ? null
                : "Its records of staged checkpoints (" + row.stagedCount() + ") will be removed too.";
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "gpu" -> "This node still has GPUs. Move or delete them first (GPUs tab), or take the node "
                    + "out of service instead: Edit, then set Status to DOWN.";
            default -> null;
        };
    }

    private static final class Form extends EntityForm<NodeRow> {

        private final int id; // 0 for a new node
        private final JTextField hostname = new JTextField(20);
        private final JComboBox<String> partition = new JComboBox<>(Allowed.PARTITIONS.toArray(new String[0]));
        private final JTextField cpuCores = new JTextField(20);
        private final JTextField ramGb = new JTextField(20);
        private final JTextField nvmeTb = new JTextField(20);
        private final JTextField maxCuda = new JTextField(20);
        private final JComboBox<String> status = new JComboBox<>(Allowed.NODE_STATUSES.toArray(new String[0]));
        private final JTextField rack = new JTextField(20);

        Form(NodeRow existing) {
            addField("Hostname", hostname);
            addField("Partition", partition);
            addField("CPU cores", cpuCores);
            addField("RAM (GB)", ramGb);
            addField("Local NVMe (TB)", nvmeTb);
            addField("Max CUDA version", maxCuda);
            addField("Status", status);
            addField("Rack (optional)", rack);

            id = existing == null ? 0 : existing.node().nodeId();
            if (existing != null) {
                ComputeNode n = existing.node();
                hostname.setText(n.hostname());
                partition.setSelectedItem(n.partitionName());
                cpuCores.setText(String.valueOf(n.cpuCores()));
                ramGb.setText(String.valueOf(n.ramGb()));
                nvmeTb.setText(n.localNvmeTb().toPlainString());
                maxCuda.setText(n.maxCudaVersion().toPlainString());
                status.setSelectedItem(n.nodeStatus());
                rack.setText(n.rackLocation()); // null shows as blank
            }
        }

        @Override
        protected NodeRow read() {
            ComputeNode node = new ComputeNode(id,
                    Inputs.requiredText("Hostname", hostname, 40),
                    (String) partition.getSelectedItem(),
                    Inputs.requiredInt("CPU cores", cpuCores, 1, 512),
                    Inputs.requiredInt("RAM", ramGb, 1, 8192),
                    Inputs.requiredInRange("Local NVMe", nvmeTb,
                            BigDecimal.ZERO, new BigDecimal("999.99"), 2), // NUMERIC(5,2)
                    Inputs.requiredInRange("Max CUDA version", maxCuda,
                            new BigDecimal("9.0"), new BigDecimal("20.0"), 1),
                    (String) status.getSelectedItem(),
                    Inputs.optionalText("Rack", rack, 10));
            // The joined columns are not saved; the list is reloaded afterwards.
            return new NodeRow(node, 0, null, 0, null);
        }
    }
}
