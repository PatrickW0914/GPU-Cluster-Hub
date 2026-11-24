package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.ComputeNode;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.util.List;

/** Compute nodes. */
public final class NodesPanel extends EntityPanel<ComputeNode> {

    private static final List<Column<ComputeNode>> COLUMNS = List.of(
            new Column<>("Hostname", String.class, ComputeNode::hostname),
            new Column<>("Partition", String.class, ComputeNode::partitionName),
            new Column<>("CPU cores", Integer.class, ComputeNode::cpuCores),
            new Column<>("RAM (GB)", Integer.class, ComputeNode::ramGb),
            new Column<>("NVMe (TB)", BigDecimal.class, ComputeNode::localNvmeTb),
            new Column<>("Max CUDA", BigDecimal.class, ComputeNode::maxCudaVersion),
            new Column<>("Status", String.class, ComputeNode::nodeStatus),
            new Column<>("Rack", String.class, ComputeNode::rackLocation));

    private final JTextField hostname = new JTextField(14);
    private final FilterBox partition = new FilterBox(Allowed.PARTITIONS);
    private final FilterBox status = new FilterBox(Allowed.NODE_STATUSES);

    public NodesPanel() {
        super("Node", COLUMNS);
        addFilter("Hostname", hostname);
        addFilter("Partition", partition);
        addFilter("Status", status);
    }

    @Override
    protected Transactions.Work<List<ComputeNode>> search() {
        String hostnameText = hostname.getText();
        String partitionValue = partition.value();
        String statusValue = status.value();
        return c -> new NodeDao(c).search(hostnameText, partitionValue, statusValue, null);
    }
}
