package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GpuTypeDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.GpuType;
import edu.ucr.cs166.gpuhub.entity.GpuTypeRow;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;
import edu.ucr.cs166.gpuhub.ui.Inputs;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

/** GPU types. The node filter searches through the gpu table. */
public final class GpuTypesPanel extends EntityPanel<GpuTypeRow> implements Editor<GpuTypeRow> {

    private static final List<Column<GpuTypeRow>> COLUMNS = List.of(
            new Column<>("Type", String.class, r -> r.type().typeName()),
            new Column<>("Vendor", String.class, r -> r.type().vendor()),
            new Column<>("Architecture", String.class, r -> r.type().architecture()),
            new Column<>("VRAM (GB)", Integer.class, r -> r.type().vramGb()),
            new Column<>("Compute capability", BigDecimal.class, r -> r.type().computeCapability()),
            new Column<>("FP16 TFLOPS", BigDecimal.class, r -> r.type().fp16Tflops()),
            new Column<>("bf16", Boolean.class, r -> r.type().supportsBf16()),
            new Column<>("GPUs", Integer.class, GpuTypeRow::gpuCount));

    private static final int MAX_SMALLINT = 32767; // vram_gb is a SMALLINT

    private final JTextField typeName = new JTextField(14);
    private final FilterBox vendor = new FilterBox(Allowed.VENDORS);
    private final JTextField minVram = new JTextField(5);
    private final FilterBox bf16 = FilterBox.yesNo();
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Any");

    public GpuTypesPanel() {
        super("GPU type", COLUMNS);
        addFilter("Type", typeName);
        addFilter("Vendor", vendor);
        addFilter("Min VRAM (GB)", minVram);
        addFilter("bf16", bf16);
        addFilter("Installed in node", node);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> nodes = NodesPanel.choices(new NodeDao(c));
            return () -> node.setChoices(nodes);
        };
    }

    @Override
    protected Transactions.Work<List<GpuTypeRow>> search() {
        String typeNameText = typeName.getText();
        String vendorValue = vendor.value();
        Integer minVramValue = Inputs.optionalInt("Min VRAM", minVram, 0, MAX_SMALLINT);
        Boolean bf16Value = bf16.booleanValue();
        Integer nodeId = node.value();
        return c -> new GpuTypeDao(c).search(typeNameText, vendorValue, minVramValue, bf16Value, nodeId);
    }

    /** All GPU types as dropdown choices. */
    static List<Choice<Integer>> choices(GpuTypeDao types) throws SQLException {
        return types.search(null, null, null, null, null).stream()
                .map(r -> new Choice<>(r.type().gpuTypeId(), r.type().typeName()))
                .toList();
    }

    @Override
    public EntityForm<GpuTypeRow> form(GpuTypeRow existing) {
        return new Form(existing);
    }

    @Override
    public Transactions.Work<Boolean> insert(GpuTypeRow row) {
        return c -> new GpuTypeDao(c).insert(row.type()) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(GpuTypeRow old, GpuTypeRow edited) {
        return c -> new GpuTypeDao(c).update(edited.type());
    }

    @Override
    public Transactions.Work<Boolean> delete(GpuTypeRow row) {
        return c -> new GpuTypeDao(c).delete(row.type().gpuTypeId());
    }

    @Override
    public String nameOf(GpuTypeRow row) {
        return row.type().typeName();
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "gpu" -> "GPUs of this type are installed. Change their type or delete them first (GPUs tab).";
            default -> null;
        };
    }

    private static final class Form extends EntityForm<GpuTypeRow> {

        private final int id; // 0 for a new type
        private final JTextField typeName = new JTextField(20);
        private final JComboBox<String> vendor = new JComboBox<>(Allowed.VENDORS.toArray(new String[0]));
        private final JTextField architecture = new JTextField(20);
        private final JTextField vramGb = new JTextField(20);
        private final JTextField computeCapability = new JTextField(20);
        private final JTextField fp16Tflops = new JTextField(20);
        private final JCheckBox supportsBf16 = new JCheckBox("supported");

        Form(GpuTypeRow existing) {
            addField("Type name", typeName);
            addField("Vendor", vendor);
            addField("Architecture", architecture);
            addField("VRAM (GB)", vramGb);
            addField("Compute capability", computeCapability);
            addField("FP16 TFLOPS", fp16Tflops);
            addField("bf16", supportsBf16);

            id = existing == null ? 0 : existing.type().gpuTypeId();
            if (existing != null) {
                GpuType t = existing.type();
                typeName.setText(t.typeName());
                vendor.setSelectedItem(t.vendor());
                architecture.setText(t.architecture());
                vramGb.setText(String.valueOf(t.vramGb()));
                computeCapability.setText(t.computeCapability().toPlainString());
                fp16Tflops.setText(t.fp16Tflops().toPlainString());
                supportsBf16.setSelected(t.supportsBf16());
            }
        }

        @Override
        protected GpuTypeRow read() {
            GpuType type = new GpuType(id,
                    Inputs.requiredText("Type name", typeName, 40),
                    (String) vendor.getSelectedItem(),
                    Inputs.requiredText("Architecture", architecture, 20),
                    Inputs.requiredInt("VRAM", vramGb, 1, MAX_SMALLINT),
                    Inputs.requiredPositive("Compute capability", computeCapability, 3, 1),
                    Inputs.requiredPositive("FP16 TFLOPS", fp16Tflops, 7, 1),
                    supportsBf16.isSelected());
            return new GpuTypeRow(type, 0); // the count is not saved; the list is reloaded afterwards
        }
    }
}
