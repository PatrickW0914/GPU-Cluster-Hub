package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.EnvRow;
import edu.ucr.cs166.gpuhub.entity.SoftwareEnv;
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
import java.time.LocalDate;
import java.util.List;

/** Software environments. Two filters search related tables: nodes (CUDA), and compatibility. */
public final class EnvsPanel extends EntityPanel<EnvRow> implements Editor<EnvRow> {

    private static final List<Column<EnvRow>> COLUMNS = List.of(
            new Column<>("Environment", String.class, r -> r.env().envName()),
            new Column<>("Python", String.class, r -> r.env().pythonVersion()),
            new Column<>("CUDA", BigDecimal.class, r -> r.env().cudaVersion()),
            new Column<>("Framework", String.class, r -> r.env().framework()),
            new Column<>("Framework version", String.class, r -> r.env().frameworkVersion()),
            new Column<>("Verified versions", Integer.class, EnvRow::versionCount),
            new Column<>("Container image", String.class, r -> r.env().containerImage()),
            new Column<>("Created", LocalDate.class, r -> r.env().createdOn()));

    private final JTextField name = new JTextField(12);
    private final FilterBox framework = new FilterBox(Allowed.FRAMEWORKS);
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Any");
    private final ChoiceBox<VersionKey> version = new ChoiceBox<>("Any");

    public EnvsPanel() {
        super("Environment", COLUMNS);
        addFilter("Name", name);
        addFilter("Framework", framework);
        addFilter("CUDA supported by node", node);
        addFilter("Verified for model version", version);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> nodes = NodesPanel.choices(new NodeDao(c));
            List<Choice<VersionKey>> versions = VersionsPanel.choices(new ModelVersionDao(c));
            return () -> {
                node.setChoices(nodes);
                version.setChoices(versions);
            };
        };
    }

    @Override
    protected Transactions.Work<List<EnvRow>> search() {
        String nameText = name.getText();
        String frameworkValue = framework.value();
        Integer nodeId = node.value();
        VersionKey supported = version.value();
        return c -> new EnvDao(c).search(nameText, frameworkValue, nodeId, supported);
    }

    /** All environments as dropdown choices. */
    static List<Choice<Integer>> choices(EnvDao envs) throws SQLException {
        return envs.search(null, null, null, null).stream()
                .map(r -> new Choice<>(r.env().envId(), r.env().envName()))
                .toList();
    }

    @Override
    public EntityForm<EnvRow> form(EnvRow existing) {
        return new Form(existing);
    }

    @Override
    public Transactions.Work<Boolean> insert(EnvRow row) {
        return c -> new EnvDao(c).insert(row.env()) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(EnvRow old, EnvRow edited) {
        return c -> new EnvDao(c).update(edited.env());
    }

    @Override
    public Transactions.Work<Boolean> delete(EnvRow row) {
        return c -> new EnvDao(c).delete(row.env().envId());
    }

    @Override
    public String nameOf(EnvRow row) {
        return row.env().envName();
    }

    @Override
    public String deleteWarning(EnvRow row) {
        return row.versionCount() == 0 ? null
                : "Its compatibility records (" + row.versionCount() + ") will be removed too.";
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "job" -> "Jobs have run in this environment, and jobs are kept as history. To stop new jobs from "
                    + "using it for a model version, delete that record in the Compatibility tab.";
            default -> null;
        };
    }

    private static final class Form extends EntityForm<EnvRow> {

        private final SoftwareEnv existing; // null for a new environment
        private final JTextField name = new JTextField(26);
        private final JTextField python = new JTextField(26);
        private final JTextField cuda = new JTextField(26);
        private final JComboBox<String> framework = new JComboBox<>(Allowed.FRAMEWORKS.toArray(new String[0]));
        private final JTextField frameworkVersion = new JTextField(26);
        private final JTextField image = new JTextField(26);

        Form(EnvRow row) {
            existing = row == null ? null : row.env();
            addField("Environment name", name);
            addField("Python version", python);
            addField("CUDA version", cuda);
            addField("Framework", framework);
            addField("Framework version", frameworkVersion);
            addField("Container image (optional)", image);
            if (existing != null) {
                name.setText(existing.envName());
                python.setText(existing.pythonVersion());
                cuda.setText(existing.cudaVersion().toPlainString());
                framework.setSelectedItem(existing.framework());
                frameworkVersion.setText(existing.frameworkVersion());
                image.setText(existing.containerImage()); // null shows as blank
            }
        }

        @Override
        protected EnvRow read() {
            SoftwareEnv env = new SoftwareEnv(
                    existing == null ? 0 : existing.envId(),
                    Inputs.requiredText("Environment name", name, 40),
                    // the same pattern as ck_env_python
                    Inputs.requiredMatch("Python version", python, 8, "3\\.[0-9]{1,2}",
                            SqlErrors.rule("ck_env_python")),
                    Inputs.requiredInRange("CUDA version", cuda, new BigDecimal("9.0"), new BigDecimal("20.0"), 1),
                    (String) framework.getSelectedItem(),
                    Inputs.requiredText("Framework version", frameworkVersion, 16),
                    Inputs.optionalText("Container image", image, 200),
                    existing == null ? null : existing.createdOn());
            return new EnvRow(env, 0); // the count is not saved; the list is reloaded afterwards
        }
    }
}
