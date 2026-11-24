package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.dao.ModelDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.ModelVersion;
import edu.ucr.cs166.gpuhub.entity.VersionKey;
import edu.ucr.cs166.gpuhub.entity.VersionRow;
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
import java.time.LocalDate;
import java.util.List;

/** Model versions (checkpoints). Two filters search related tables: staged copies, and compatibility. */
public final class VersionsPanel extends EntityPanel<VersionRow> implements Editor<VersionRow> {

    private static final List<Column<VersionRow>> COLUMNS = List.of(
            new Column<>("Model", String.class, VersionRow::modelName),
            new Column<>("Version", String.class, r -> r.version().versionTag()),
            new Column<>("Parameters (B)", BigDecimal.class, r -> r.version().paramCountB()),
            new Column<>("Precision", String.class, r -> r.version().weightPrecision()),
            new Column<>("Min VRAM (GB)", BigDecimal.class, r -> r.version().minVramGb()),
            new Column<>("Size (GB)", BigDecimal.class, r -> r.version().checkpointSizeGb()),
            new Column<>("Nodes", Integer.class, VersionRow::nodeCount),
            new Column<>("Envs", Integer.class, VersionRow::envCount),
            new Column<>("Released", LocalDate.class, r -> r.version().releasedOn()),
            new Column<>("Deprecated", Boolean.class, r -> r.version().isDeprecated()));

    private static final BigDecimal MAX_VRAM = new BigDecimal("99999.9"); // NUMERIC(6,1)

    private final ChoiceBox<Integer> model = new ChoiceBox<>("Any");
    private final FilterBox precision = new FilterBox(Allowed.PRECISIONS);
    private final JTextField vram = new JTextField(5);
    private final FilterBox deprecated = FilterBox.yesNo();
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Any");
    private final ChoiceBox<Integer> env = new ChoiceBox<>("Any");

    private List<Choice<Integer>> modelChoices = List.of(); // for the form's model dropdown

    public VersionsPanel() {
        super("Model version", COLUMNS);
        addFilter("Model", model);
        addFilter("Precision", precision);
        addFilter("Fits in VRAM (GB)", vram);
        addFilter("Deprecated", deprecated);
        addFilter("Staged on node", node);
        addFilter("Verified in environment", env);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> models = ModelsPanel.choices(new ModelDao(c));
            List<Choice<Integer>> nodes = NodesPanel.choices(new NodeDao(c));
            List<Choice<Integer>> envs = EnvsPanel.choices(new EnvDao(c));
            return () -> {
                modelChoices = models;
                model.setChoices(models);
                node.setChoices(nodes);
                env.setChoices(envs);
            };
        };
    }

    @Override
    protected Transactions.Work<List<VersionRow>> search() {
        Integer modelId = model.value();
        String precisionValue = precision.value();
        BigDecimal maxVram = Inputs.optionalInRange("Fits in VRAM", vram, BigDecimal.ZERO, MAX_VRAM, 1);
        Boolean deprecatedValue = deprecated.booleanValue();
        Integer nodeId = node.value();
        Integer envId = env.value();
        return c -> new ModelVersionDao(c)
                .search(modelId, precisionValue, maxVram, deprecatedValue, nodeId, envId);
    }

    /** All model versions as dropdown choices. */
    static List<Choice<VersionKey>> choices(ModelVersionDao versions) throws SQLException {
        return versions.search(null, null, null, null, null, null).stream()
                .map(r -> new Choice<>(new VersionKey(r.version().modelId(), r.version().versionTag()),
                        r.modelName() + " : " + r.version().versionTag()))
                .toList();
    }

    @Override
    public EntityForm<VersionRow> form(VersionRow existing) {
        return new Form(existing, modelChoices);
    }

    @Override
    public Transactions.Work<Boolean> insert(VersionRow row) {
        return c -> {
            new ModelVersionDao(c).insert(row.version());
            return true;
        };
    }

    /** The old tag identifies the row, because the edit may rename it. */
    @Override
    public Transactions.Work<Boolean> update(VersionRow old, VersionRow edited) {
        return c -> new ModelVersionDao(c).update(old.version().versionTag(), edited.version());
    }

    @Override
    public Transactions.Work<Boolean> delete(VersionRow row) {
        return c -> new ModelVersionDao(c).delete(row.version().modelId(), row.version().versionTag());
    }

    @Override
    public String nameOf(VersionRow row) {
        return row.modelName() + " : " + row.version().versionTag();
    }

    @Override
    public String deleteWarning(VersionRow row) {
        return row.nodeCount() + row.envCount() == 0 ? null
                : "Its staged copies (" + row.nodeCount() + ") and compatibility records ("
                        + row.envCount() + ") will be removed too.";
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "job_loads_version" -> "Jobs have loaded this version, and jobs are kept as history. "
                    + "Mark it as deprecated instead: Edit, then tick Deprecated.";
            default -> null;
        };
    }

    /** The model is part of the key, so it can only be chosen when adding. The tag can be renamed. */
    private static final class Form extends EntityForm<VersionRow> {

        private final VersionRow existing; // null for a new version
        private final ChoiceBox<Integer> model = new ChoiceBox<>("Select a model");
        private final JTextField tag = new JTextField(20);
        private final JTextField params = new JTextField(20);
        private final JComboBox<String> precision = new JComboBox<>(Allowed.PRECISIONS.toArray(new String[0]));
        private final JTextField minVram = new JTextField(20);
        private final JTextField size = new JTextField(20);
        private final JTextField releasedOn = new JTextField(20);
        private final JCheckBox deprecated = new JCheckBox("should no longer be used");

        Form(VersionRow existing, List<Choice<Integer>> modelChoices) {
            this.existing = existing;
            addField("Model", model);
            addField("Version tag", tag);
            addField("Parameters (billions)", params);
            addField("Precision", precision);
            addField("Min VRAM (GB)", minVram);
            addField("Checkpoint size (GB)", size);
            addField("Released on (optional)", releasedOn);
            addField("Deprecated", deprecated);
            model.setChoices(modelChoices);
            if (existing != null) {
                ModelVersion v = existing.version();
                model.select(v.modelId());
                model.setEnabled(false);
                tag.setText(v.versionTag());
                params.setText(v.paramCountB().toPlainString());
                precision.setSelectedItem(v.weightPrecision());
                minVram.setText(v.minVramGb().toPlainString());
                size.setText(v.checkpointSizeGb().toPlainString());
                releasedOn.setText(v.releasedOn() == null ? "" : v.releasedOn().toString());
                deprecated.setSelected(v.isDeprecated());
            }
        }

        @Override
        protected VersionRow read() {
            ModelVersion version = new ModelVersion(
                    existing == null ? Inputs.requiredChoice("Model", model) : existing.version().modelId(),
                    Inputs.requiredText("Version tag", tag, 30),
                    Inputs.requiredPositive("Parameters", params, 8, 3),
                    (String) precision.getSelectedItem(),
                    Inputs.requiredPositive("Min VRAM", minVram, 6, 1),
                    Inputs.requiredPositive("Checkpoint size", size, 8, 2),
                    Inputs.optionalDate("Released on", releasedOn),
                    deprecated.isSelected());
            // The joined columns are not saved; the list is reloaded afterwards.
            return new VersionRow(version, model.getSelectedItem().toString(), 0, 0);
        }
    }
}
