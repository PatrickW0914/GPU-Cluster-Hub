package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GpuTypeDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.GpuType;
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
import java.util.List;

/** GPU types. The reference screen: search, add, edit and delete in the shared layout. */
public final class GpuTypesPanel extends EntityPanel<GpuType> implements Editor<GpuType> {

    private static final List<Column<GpuType>> COLUMNS = List.of(
            new Column<>("Type", String.class, GpuType::typeName),
            new Column<>("Vendor", String.class, GpuType::vendor),
            new Column<>("Architecture", String.class, GpuType::architecture),
            new Column<>("VRAM (GB)", Integer.class, GpuType::vramGb),
            new Column<>("Compute capability", BigDecimal.class, GpuType::computeCapability),
            new Column<>("FP16 TFLOPS", BigDecimal.class, GpuType::fp16Tflops),
            new Column<>("bf16", Boolean.class, GpuType::supportsBf16));

    private static final int MAX_SMALLINT = 32767; // vram_gb is a SMALLINT

    private final JTextField typeName = new JTextField(14);
    private final FilterBox vendor = new FilterBox(Allowed.VENDORS);
    private final JTextField minVram = new JTextField(5);
    private final FilterBox bf16 = FilterBox.yesNo();

    public GpuTypesPanel() {
        super("GPU type", COLUMNS);
        addFilter("Type", typeName);
        addFilter("Vendor", vendor);
        addFilter("Min VRAM (GB)", minVram);
        addFilter("bf16", bf16);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<List<GpuType>> search() {
        String typeNameText = typeName.getText();
        String vendorValue = vendor.value();
        Integer minVramValue = Inputs.optionalInt("Min VRAM", minVram, 0, MAX_SMALLINT);
        Boolean bf16Value = bf16.booleanValue();
        return c -> new GpuTypeDao(c).search(typeNameText, vendorValue, minVramValue, bf16Value);
    }

    @Override
    public EntityForm<GpuType> form(GpuType existing) {
        return new Form(existing);
    }

    @Override
    public Transactions.Work<Boolean> insert(GpuType type) {
        return c -> new GpuTypeDao(c).insert(type) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(GpuType old, GpuType edited) {
        return c -> new GpuTypeDao(c).update(edited);
    }

    @Override
    public Transactions.Work<Boolean> delete(GpuType type) {
        return c -> new GpuTypeDao(c).delete(type.gpuTypeId());
    }

    @Override
    public String nameOf(GpuType type) {
        return type.typeName();
    }

    private static final class Form extends EntityForm<GpuType> {

        private final int id; // 0 for a new type
        private final JTextField typeName = new JTextField(20);
        private final JComboBox<String> vendor = new JComboBox<>(Allowed.VENDORS.toArray(new String[0]));
        private final JTextField architecture = new JTextField(20);
        private final JTextField vramGb = new JTextField(20);
        private final JTextField computeCapability = new JTextField(20);
        private final JTextField fp16Tflops = new JTextField(20);
        private final JCheckBox supportsBf16 = new JCheckBox("supported");

        Form(GpuType existing) {
            addField("Type name", typeName);
            addField("Vendor", vendor);
            addField("Architecture", architecture);
            addField("VRAM (GB)", vramGb);
            addField("Compute capability", computeCapability);
            addField("FP16 TFLOPS", fp16Tflops);
            addField("bf16", supportsBf16);

            id = existing == null ? 0 : existing.gpuTypeId();
            if (existing != null) {
                typeName.setText(existing.typeName());
                vendor.setSelectedItem(existing.vendor());
                architecture.setText(existing.architecture());
                vramGb.setText(String.valueOf(existing.vramGb()));
                computeCapability.setText(existing.computeCapability().toPlainString());
                fp16Tflops.setText(existing.fp16Tflops().toPlainString());
                supportsBf16.setSelected(existing.supportsBf16());
            }
        }

        @Override
        protected GpuType read() {
            return new GpuType(id,
                    Inputs.requiredText("Type name", typeName, 40),
                    (String) vendor.getSelectedItem(),
                    Inputs.requiredText("Architecture", architecture, 20),
                    Inputs.requiredInt("VRAM", vramGb, 1, MAX_SMALLINT),
                    Inputs.requiredPositive("Compute capability", computeCapability, 3, 1),
                    Inputs.requiredPositive("FP16 TFLOPS", fp16Tflops, 7, 1),
                    supportsBf16.isSelected());
        }
    }
}
