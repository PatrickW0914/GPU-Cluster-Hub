package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GpuDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Gpu;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import javax.swing.JTextField;
import java.time.LocalDate;
import java.util.List;

/** Individual GPUs. */
public final class GpusPanel extends EntityPanel<Gpu> {

    private static final List<Column<Gpu>> COLUMNS = List.of(
            new Column<>("GPU id", Integer.class, Gpu::gpuId),
            new Column<>("Node id", Integer.class, Gpu::nodeId),
            new Column<>("Slot", Integer.class, Gpu::slotIndex),
            new Column<>("GPU type id", Integer.class, Gpu::gpuTypeId),
            new Column<>("Serial number", String.class, Gpu::serialNumber),
            new Column<>("Status", String.class, Gpu::gpuStatus),
            new Column<>("Installed", LocalDate.class, Gpu::installedOn));

    private final JTextField serial = new JTextField(14);
    private final FilterBox status = new FilterBox(Allowed.GPU_STATUSES);

    public GpusPanel() {
        super("GPU", COLUMNS);
        addFilter("Serial number", serial);
        addFilter("Status", status);
    }

    @Override
    protected Transactions.Work<List<Gpu>> search() {
        String serialText = serial.getText();
        String statusValue = status.value();
        return c -> new GpuDao(c).search(null, null, statusValue, serialText);
    }
}
