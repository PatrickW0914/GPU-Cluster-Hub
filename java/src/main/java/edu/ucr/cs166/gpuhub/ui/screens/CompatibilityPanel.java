package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.CompatibilityDao;
import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.Compatibility;
import edu.ucr.cs166.gpuhub.entity.CompatibilityRow;
import edu.ucr.cs166.gpuhub.entity.VersionKey;
import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.Inputs;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Which model versions were verified in which environments. A job may only load verified versions. */
public final class CompatibilityPanel extends EntityPanel<CompatibilityRow> implements Editor<CompatibilityRow> {

    private static final List<Column<CompatibilityRow>> COLUMNS = List.of(
            new Column<>("Model", String.class, CompatibilityRow::modelName),
            new Column<>("Version", String.class, r -> r.compatibility().versionTag()),
            new Column<>("Environment", String.class, CompatibilityRow::envName),
            new Column<>("CUDA", BigDecimal.class, CompatibilityRow::cudaVersion),
            new Column<>("Verified", LocalDate.class, r -> r.compatibility().verifiedOn()),
            new Column<>("Notes", String.class, r -> r.compatibility().notes()));

    private final AppClock clock;
    private final ChoiceBox<VersionKey> version = new ChoiceBox<>("Any");
    private final ChoiceBox<Integer> env = new ChoiceBox<>("Any");
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Any");

    private List<Choice<VersionKey>> versionChoices = List.of(); // for the form's dropdowns
    private List<Choice<Integer>> envChoices = List.of();

    public CompatibilityPanel(AppClock clock) {
        super("Compatibility record", COLUMNS);
        this.clock = clock;
        addFilter("Model version", version);
        addFilter("Environment", env);
        addFilter("CUDA supported by node", node);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<VersionKey>> versions = VersionsPanel.choices(new ModelVersionDao(c));
            List<Choice<Integer>> envs = EnvsPanel.choices(new EnvDao(c));
            List<Choice<Integer>> nodes = NodesPanel.choices(new NodeDao(c));
            return () -> {
                versionChoices = versions;
                envChoices = envs;
                version.setChoices(versions);
                env.setChoices(envs);
                node.setChoices(nodes);
            };
        };
    }

    @Override
    protected Transactions.Work<List<CompatibilityRow>> search() {
        Integer envId = env.value();
        VersionKey versionKey = version.value();
        Integer nodeId = node.value();
        return c -> new CompatibilityDao(c).search(envId, versionKey, nodeId);
    }

    @Override
    public EntityForm<CompatibilityRow> form(CompatibilityRow existing) {
        return new Form(existing, versionChoices, envChoices, clock.now().toLocalDate());
    }

    @Override
    public Transactions.Work<Boolean> insert(CompatibilityRow row) {
        return c -> {
            new CompatibilityDao(c).insert(row.compatibility());
            return true;
        };
    }

    @Override
    public Transactions.Work<Boolean> update(CompatibilityRow old, CompatibilityRow edited) {
        return c -> new CompatibilityDao(c).update(edited.compatibility());
    }

    @Override
    public Transactions.Work<Boolean> delete(CompatibilityRow row) {
        return c -> new CompatibilityDao(c).delete(row.compatibility());
    }

    @Override
    public String nameOf(CompatibilityRow row) {
        return row.modelName() + " : " + row.compatibility().versionTag() + " in " + row.envName();
    }

    @Override
    public String deleteWarning(CompatibilityRow row) {
        return "New jobs in this environment will not be able to load this version.";
    }

    /** The version and environment are the key, so they can only be chosen when adding. */
    private static final class Form extends EntityForm<CompatibilityRow> {

        private final CompatibilityRow existing; // null for a new record
        private final ChoiceBox<VersionKey> version = new ChoiceBox<>("Select a model version");
        private final ChoiceBox<Integer> env = new ChoiceBox<>("Select an environment");
        private final JTextField verifiedOn = new JTextField(22);
        private final JTextField notes = new JTextField(22);

        Form(CompatibilityRow existing, List<Choice<VersionKey>> versionChoices,
             List<Choice<Integer>> envChoices, LocalDate today) {
            this.existing = existing;
            addField("Model version", version);
            addField("Environment", env);
            addField("Verified on (yyyy-mm-dd)", verifiedOn);
            addField("Notes (optional)", notes);
            version.setChoices(versionChoices);
            env.setChoices(envChoices);
            if (existing == null) {
                verifiedOn.setText(today.toString());
            } else {
                Compatibility s = existing.compatibility();
                version.select(new VersionKey(s.modelId(), s.versionTag()));
                env.select(s.envId());
                version.setEnabled(false);
                env.setEnabled(false);
                verifiedOn.setText(s.verifiedOn().toString());
                notes.setText(s.notes()); // null shows as blank
            }
        }

        @Override
        protected CompatibilityRow read() {
            VersionKey key = Inputs.requiredChoice("Model version", version);
            int envId = Inputs.requiredChoice("Environment", env);
            Compatibility compatibility = new Compatibility(envId, key.modelId(), key.versionTag(),
                    Inputs.requiredDate("Verified on", verifiedOn),
                    Inputs.optionalText("Notes", notes, 200));
            // The names are only used in the success message; the list is reloaded afterwards.
            String modelName = version.getSelectedItem().toString().split(" : ")[0];
            return new CompatibilityRow(compatibility, env.getSelectedItem().toString(), null, modelName);
        }
    }
}
