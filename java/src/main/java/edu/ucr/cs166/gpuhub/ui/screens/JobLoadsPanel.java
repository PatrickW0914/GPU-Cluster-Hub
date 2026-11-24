package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.JobLoadDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.JobLoad;
import edu.ucr.cs166.gpuhub.entity.JobLoadRow;
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
import java.util.List;

/** The model versions each job loads. The user filter searches through jobs and reservations. */
public final class JobLoadsPanel extends EntityPanel<JobLoadRow> implements Editor<JobLoadRow> {

    private static final List<Column<JobLoadRow>> COLUMNS = List.of(
            new Column<>("Job", Long.class, r -> r.load().jobId()),
            new Column<>("Job name", String.class, JobLoadRow::jobName),
            new Column<>("User", String.class, JobLoadRow::netId),
            new Column<>("Environment", String.class, JobLoadRow::envName),
            new Column<>("Model", String.class, JobLoadRow::modelName),
            new Column<>("Version", String.class, r -> r.load().versionTag()),
            new Column<>("Usage", String.class, r -> r.load().usageRole()));

    private final JTextField job = new JTextField(6);
    private final JTextField user = new JTextField(10);
    private final ChoiceBox<VersionKey> version = new ChoiceBox<>("Any");
    private final FilterBox role = new FilterBox(Allowed.USAGE_ROLES);

    private List<Choice<VersionKey>> versionChoices = List.of(); // for the form's version dropdown

    public JobLoadsPanel() {
        super("Model load", COLUMNS);
        addFilter("Job number", job);
        addFilter("User (NetID or name)", user);
        addFilter("Model version", version);
        addFilter("Usage", role);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<VersionKey>> versions = VersionsPanel.choices(new ModelVersionDao(c));
            return () -> {
                versionChoices = versions;
                version.setChoices(versions);
            };
        };
    }

    @Override
    protected Transactions.Work<List<JobLoadRow>> search() {
        Integer jobId = Inputs.optionalInt("Job number", job, 1, Integer.MAX_VALUE);
        String userText = user.getText();
        VersionKey versionKey = version.value();
        String roleValue = role.value();
        return c -> new JobLoadDao(c).search(jobId, userText, versionKey, roleValue);
    }

    @Override
    public EntityForm<JobLoadRow> form(JobLoadRow existing) {
        return new Form(existing, versionChoices);
    }

    @Override
    public Transactions.Work<Boolean> insert(JobLoadRow row) {
        return c -> {
            new JobLoadDao(c).insert(row.load());
            return true;
        };
    }

    @Override
    public Transactions.Work<Boolean> update(JobLoadRow old, JobLoadRow edited) {
        return c -> new JobLoadDao(c).update(edited.load());
    }

    @Override
    public Transactions.Work<Boolean> delete(JobLoadRow row) {
        return c -> new JobLoadDao(c).delete(row.load());
    }

    @Override
    public String nameOf(JobLoadRow row) {
        return "of " + row.modelName() + " : " + row.load().versionTag() + " by job " + row.load().jobId();
    }

    /** The job and version are the key, so they can only be chosen when adding. */
    private static final class Form extends EntityForm<JobLoadRow> {

        private final JTextField job = new JTextField(20);
        private final ChoiceBox<VersionKey> version = new ChoiceBox<>("Select a model version");
        private final JComboBox<String> role = new JComboBox<>(Allowed.USAGE_ROLES.toArray(new String[0]));

        Form(JobLoadRow existing, List<Choice<VersionKey>> versionChoices) {
            addField("Job number", job);
            addField("Model version", version);
            addField("Usage", role);
            version.setChoices(versionChoices);
            if (existing != null) {
                JobLoad l = existing.load();
                job.setText(String.valueOf(l.jobId()));
                version.select(new VersionKey(l.modelId(), l.versionTag()));
                role.setSelectedItem(l.usageRole());
                job.setEnabled(false);
                version.setEnabled(false);
            }
        }

        @Override
        protected JobLoadRow read() {
            int jobId = Inputs.requiredInt("Job number", job, 1, Integer.MAX_VALUE);
            VersionKey key = Inputs.requiredChoice("Model version", version);
            JobLoad load = new JobLoad(jobId, key.modelId(), key.versionTag(), (String) role.getSelectedItem());
            // The names are only used in the success message; the list is reloaded afterwards.
            String modelName = version.getSelectedItem().toString().split(" : ")[0];
            return new JobLoadRow(load, null, null, null, modelName);
        }
    }
}
