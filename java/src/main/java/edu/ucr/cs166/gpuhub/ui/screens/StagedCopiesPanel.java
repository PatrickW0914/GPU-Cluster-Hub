package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.dao.StagedCopyDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.StagedCopy;
import edu.ucr.cs166.gpuhub.entity.StagedCopyRow;
import edu.ucr.cs166.gpuhub.entity.VersionKey;
import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;
import edu.ucr.cs166.gpuhub.ui.Inputs;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Which checkpoints are stored on which nodes. The task filter searches through the model table. */
public final class StagedCopiesPanel extends EntityPanel<StagedCopyRow> implements Editor<StagedCopyRow> {

    private static final List<Column<StagedCopyRow>> COLUMNS = List.of(
            new Column<>("Node", String.class, StagedCopyRow::hostname),
            new Column<>("Model", String.class, StagedCopyRow::modelName),
            new Column<>("Version", String.class, r -> r.copy().versionTag()),
            new Column<>("Task", String.class, StagedCopyRow::taskCategory),
            new Column<>("Size (GB)", BigDecimal.class, StagedCopyRow::sizeGb),
            new Column<>("Staged", LocalDate.class, r -> r.copy().stagedOn()),
            new Column<>("Local path", String.class, r -> r.copy().localPath()));

    private final AppClock clock;
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Any");
    private final ChoiceBox<VersionKey> version = new ChoiceBox<>("Any");
    private final FilterBox task = new FilterBox(Allowed.TASKS);
    private final JTextField path = new JTextField(12);

    private List<Choice<Integer>> nodeChoices = List.of(); // for the form's dropdowns
    private List<Choice<VersionKey>> versionChoices = List.of();

    public StagedCopiesPanel(AppClock clock) {
        super("Staged copy", COLUMNS);
        this.clock = clock;
        addFilter("Node", node);
        addFilter("Model version", version);
        addFilter("Task", task);
        addFilter("Path", path);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> nodes = NodesPanel.choices(new NodeDao(c));
            List<Choice<VersionKey>> versions = VersionsPanel.choices(new ModelVersionDao(c));
            return () -> {
                nodeChoices = nodes;
                versionChoices = versions;
                node.setChoices(nodes);
                version.setChoices(versions);
            };
        };
    }

    @Override
    protected Transactions.Work<List<StagedCopyRow>> search() {
        Integer nodeId = node.value();
        VersionKey versionKey = version.value();
        String taskValue = task.value();
        String pathText = path.getText();
        return c -> new StagedCopyDao(c).search(nodeId, versionKey, taskValue, pathText);
    }

    @Override
    public EntityForm<StagedCopyRow> form(StagedCopyRow existing) {
        return new Form(existing, nodeChoices, versionChoices, clock.now().toLocalDate());
    }

    @Override
    public Transactions.Work<Boolean> insert(StagedCopyRow row) {
        return c -> {
            new StagedCopyDao(c).insert(row.copy());
            return true;
        };
    }

    @Override
    public Transactions.Work<Boolean> update(StagedCopyRow old, StagedCopyRow edited) {
        return c -> new StagedCopyDao(c).update(edited.copy());
    }

    @Override
    public Transactions.Work<Boolean> delete(StagedCopyRow row) {
        return c -> new StagedCopyDao(c).delete(row.copy());
    }

    @Override
    public String nameOf(StagedCopyRow row) {
        return row.modelName() + " : " + row.copy().versionTag() + " on " + row.hostname();
    }

    /** The node and version are the key, so they can only be chosen when adding. */
    private static final class Form extends EntityForm<StagedCopyRow> {

        private final ChoiceBox<Integer> node = new ChoiceBox<>("Select a node");
        private final ChoiceBox<VersionKey> version = new ChoiceBox<>("Select a model version");
        private final JTextField localPath = new JTextField(28);
        private final JTextField stagedOn = new JTextField(28);

        Form(StagedCopyRow existing, List<Choice<Integer>> nodeChoices,
             List<Choice<VersionKey>> versionChoices, LocalDate today) {
            addField("Node", node);
            addField("Model version", version);
            addField("Local path", localPath);
            addField("Staged on (yyyy-mm-dd)", stagedOn);
            node.setChoices(nodeChoices);
            version.setChoices(versionChoices);
            if (existing == null) {
                localPath.setText("/scratch/models/");
                stagedOn.setText(today.toString());
            } else {
                StagedCopy h = existing.copy();
                node.select(h.nodeId());
                version.select(new VersionKey(h.modelId(), h.versionTag()));
                node.setEnabled(false);
                version.setEnabled(false);
                localPath.setText(h.localPath());
                stagedOn.setText(h.stagedOn().toString());
            }
        }

        @Override
        protected StagedCopyRow read() {
            int nodeId = Inputs.requiredChoice("Node", node);
            VersionKey key = Inputs.requiredChoice("Model version", version);
            StagedCopy copy = new StagedCopy(nodeId, key.modelId(), key.versionTag(),
                    Inputs.requiredMatch("Local path", localPath, 255, "/.*", SqlErrors.rule("ck_hosts_path")),
                    Inputs.requiredDate("Staged on", stagedOn));
            // The names are only used in the success message; the list is reloaded afterwards.
            String modelName = version.getSelectedItem().toString().split(" : ")[0];
            return new StagedCopyRow(copy, node.getSelectedItem().toString(), modelName, null, null);
        }
    }
}
