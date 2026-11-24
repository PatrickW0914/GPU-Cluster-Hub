package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.dao.ModelDao;
import edu.ucr.cs166.gpuhub.dao.NodeDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.AiModel;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.ModelRow;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;
import edu.ucr.cs166.gpuhub.ui.InputException;
import edu.ucr.cs166.gpuhub.ui.Inputs;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/** AI models. Two filters search related tables: staged copies, and compatibility. */
public final class ModelsPanel extends EntityPanel<ModelRow> implements Editor<ModelRow> {

    private static final List<Column<ModelRow>> COLUMNS = List.of(
            new Column<>("Model", String.class, r -> r.model().modelName()),
            new Column<>("Task", String.class, r -> r.model().taskCategory()),
            new Column<>("Framework", String.class, r -> r.model().framework()),
            new Column<>("Versions", Integer.class, ModelRow::versionCount),
            new Column<>("License", String.class, r -> r.model().license()),
            new Column<>("Maintainer", String.class, ModelRow::maintainerName),
            new Column<>("Added", LocalDate.class, r -> r.model().addedOn()),
            new Column<>("Description", String.class, r -> r.model().description()),
            new Column<>("Source", String.class, r -> r.model().sourceUrl()));

    private final JTextField text = new JTextField(12);
    private final FilterBox task = new FilterBox(Allowed.TASKS);
    private final FilterBox framework = new FilterBox(Allowed.FRAMEWORKS);
    private final ChoiceBox<Integer> node = new ChoiceBox<>("Any");
    private final ChoiceBox<Integer> env = new ChoiceBox<>("Any");

    private List<Choice<Integer>> userChoices = List.of(); // for the form's maintainer dropdown

    public ModelsPanel() {
        super("Model", COLUMNS);
        addFilter("Name or description", text);
        addFilter("Task", task);
        addFilter("Framework", framework);
        addFilter("Staged on node", node);
        addFilter("Verified in environment", env);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> users = UsersPanel.choices(new UserDao(c), null);
            List<Choice<Integer>> nodes = NodesPanel.choices(new NodeDao(c));
            List<Choice<Integer>> envs = EnvsPanel.choices(new EnvDao(c));
            return () -> {
                userChoices = users;
                node.setChoices(nodes);
                env.setChoices(envs);
            };
        };
    }

    @Override
    protected Transactions.Work<List<ModelRow>> search() {
        String searchText = text.getText();
        String taskValue = task.value();
        String frameworkValue = framework.value();
        Integer nodeId = node.value();
        Integer envId = env.value();
        return c -> new ModelDao(c).search(searchText, taskValue, frameworkValue, null, nodeId, envId);
    }

    /** All models as dropdown choices. */
    static List<Choice<Integer>> choices(ModelDao models) throws SQLException {
        return models.search(null, null, null, null, null, null).stream()
                .map(r -> new Choice<>(r.model().modelId(), r.model().modelName()))
                .toList();
    }

    @Override
    public EntityForm<ModelRow> form(ModelRow existing) {
        return new Form(existing, userChoices);
    }

    @Override
    public Transactions.Work<Boolean> insert(ModelRow row) {
        return c -> new ModelDao(c).insert(row.model()) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(ModelRow old, ModelRow edited) {
        return c -> new ModelDao(c).update(edited.model());
    }

    @Override
    public Transactions.Work<Boolean> delete(ModelRow row) {
        return c -> new ModelDao(c).delete(row.model().modelId());
    }

    @Override
    public String nameOf(ModelRow row) {
        return row.model().modelName();
    }

    @Override
    public String deleteWarning(ModelRow row) {
        return row.versionCount() == 0 ? null
                : "Its versions (" + row.versionCount() + ") with their staged copies and compatibility "
                        + "records will be removed too.";
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "job_loads_version" -> "Jobs have loaded this model, and jobs are kept as history. Mark its "
                    + "versions as deprecated instead: Model versions, Edit, then tick Deprecated.";
            default -> null;
        };
    }

    private static final class Form extends EntityForm<ModelRow> {

        private final AiModel existing; // null for a new model
        private final JTextField name = new JTextField(28);
        private final JComboBox<String> task = new JComboBox<>(Allowed.TASKS.toArray(new String[0]));
        private final JComboBox<String> framework = new JComboBox<>(Allowed.FRAMEWORKS.toArray(new String[0]));
        private final JTextField license = new JTextField(28);
        private final JTextField sourceUrl = new JTextField(28);
        private final ChoiceBox<Integer> maintainer = new ChoiceBox<>("Select a user");
        private final JTextField description = new JTextField(28);

        Form(ModelRow row, List<Choice<Integer>> userChoices) {
            existing = row == null ? null : row.model();
            addField("Model name", name);
            addField("Task", task);
            addField("Framework", framework);
            addField("License", license);
            addField("Source URL (optional)", sourceUrl);
            addField("Maintainer", maintainer);
            addField("Description (optional)", description);
            maintainer.setChoices(userChoices);
            if (existing != null) {
                name.setText(existing.modelName());
                task.setSelectedItem(existing.taskCategory());
                framework.setSelectedItem(existing.framework());
                license.setText(existing.license());
                sourceUrl.setText(existing.sourceUrl()); // null shows as blank
                maintainer.select(existing.maintainerId());
                description.setText(existing.description());
            }
        }

        @Override
        protected ModelRow read() {
            String nameText = Inputs.requiredText("Model name", name, 60);
            String licenseText = Inputs.requiredText("License", license, 40);
            String url = Inputs.optionalText("Source URL", sourceUrl, 200);
            if (url != null && !url.matches("https?://.*")) {
                throw new InputException(SqlErrors.rule("ck_model_url"));
            }
            AiModel model = new AiModel(
                    existing == null ? 0 : existing.modelId(),
                    nameText,
                    (String) task.getSelectedItem(),
                    (String) framework.getSelectedItem(),
                    licenseText,
                    url,
                    Inputs.requiredChoice("Maintainer", maintainer),
                    Inputs.optionalText("Description", description, 1000),
                    existing == null ? null : existing.addedOn());
            // The joined columns are not saved; the list is reloaded afterwards.
            return new ModelRow(model, null, 0);
        }
    }
}
