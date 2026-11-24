package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.dao.JobDao;
import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.Job;
import edu.ucr.cs166.gpuhub.entity.JobRow;
import edu.ucr.cs166.gpuhub.entity.VersionKey;
import edu.ucr.cs166.gpuhub.service.AppClock;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Jobs run inside reservations. Filters also search the reservation's user and the loaded models. */
public final class JobsPanel extends EntityPanel<JobRow> implements Editor<JobRow> {

    private static final List<Column<JobRow>> COLUMNS = List.of(
            new Column<>("Job", Long.class, r -> r.job().jobId()),
            new Column<>("Name", String.class, r -> r.job().jobName()),
            new Column<>("State", String.class, r -> r.job().jobState()),
            new Column<>("User", String.class, JobRow::netId),
            new Column<>("Reservation", Integer.class, r -> r.job().reservationId()),
            new Column<>("Environment", String.class, JobRow::envName),
            new Column<>("Loads", String.class, JobRow::loads),
            new Column<>("Submitted", LocalDateTime.class, r -> r.job().submitTime()),
            new Column<>("Started", LocalDateTime.class, r -> r.job().startTime()),
            new Column<>("Ended", LocalDateTime.class, r -> r.job().endTime()),
            new Column<>("Exit code", Integer.class, r -> r.job().exitCode()),
            new Column<>("Peak VRAM (GB)", BigDecimal.class, r -> r.job().peakVramGb()),
            new Column<>("GPU util (%)", BigDecimal.class, r -> r.job().avgGpuUtilPct()));

    private final AppClock clock;
    private final JTextField name = new JTextField(10);
    private final FilterBox state = new FilterBox(Allowed.JOB_STATES);
    private final JTextField reservation = new JTextField(6);
    private final JTextField user = new JTextField(10);
    private final ChoiceBox<Integer> env = new ChoiceBox<>("Any");
    private final ChoiceBox<VersionKey> loaded = new ChoiceBox<>("Any");

    private List<Choice<Integer>> envChoices = List.of(); // for the form's environment dropdown

    public JobsPanel(AppClock clock) {
        super("Job", COLUMNS);
        this.clock = clock;
        addFilter("Job name", name);
        addFilter("State", state);
        addFilter("Reservation", reservation);
        addFilter("User (NetID or name)", user);
        addFilter("Environment", env);
        addFilter("Loads model version", loaded);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> envs = EnvsPanel.choices(new EnvDao(c));
            List<Choice<VersionKey>> versions = VersionsPanel.choices(new ModelVersionDao(c));
            return () -> {
                envChoices = envs;
                env.setChoices(envs);
                loaded.setChoices(versions);
            };
        };
    }

    @Override
    protected Transactions.Work<List<JobRow>> search() {
        String nameText = name.getText();
        String stateValue = state.value();
        Integer reservationId = Inputs.optionalInt("Reservation", reservation, 1, Integer.MAX_VALUE);
        String userText = user.getText();
        Integer envId = env.value();
        VersionKey loadedVersion = loaded.value();
        return c -> new JobDao(c).search(nameText, stateValue, reservationId, userText, envId, loadedVersion);
    }

    @Override
    public EntityForm<JobRow> form(JobRow existing) {
        return new Form(existing, envChoices, clock.now());
    }

    @Override
    public Transactions.Work<Boolean> insert(JobRow row) {
        return c -> new JobDao(c).insert(row.job()) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(JobRow old, JobRow edited) {
        return c -> new JobDao(c).update(edited.job());
    }

    @Override
    public Transactions.Work<Boolean> delete(JobRow row) {
        return c -> new JobDao(c).delete(row.job().jobId());
    }

    @Override
    public String nameOf(JobRow row) {
        return row.job().jobName();
    }

    @Override
    public String deleteWarning(JobRow row) {
        return row.loadCount() == 0 ? null
                : "Its model loads (" + row.loadCount() + ") will be removed too.";
    }

    private static final class Form extends EntityForm<JobRow> {

        private static final BigDecimal MAX_VRAM = new BigDecimal("99999.9"); // NUMERIC(6,1)
        private static final BigDecimal HUNDRED = new BigDecimal("100");

        private final long id; // 0 for a new job
        private final JTextField reservation = new JTextField(20);
        private final ChoiceBox<Integer> env = new ChoiceBox<>("Select an environment");
        private final JTextField name = new JTextField(20);
        private final JComboBox<String> state = new JComboBox<>(Allowed.JOB_STATES.toArray(new String[0]));
        private final JTextField submit = new JTextField(20);
        private final JTextField start = new JTextField(20);
        private final JTextField end = new JTextField(20);
        private final JTextField exitCode = new JTextField(20);
        private final JTextField peakVram = new JTextField(20);
        private final JTextField util = new JTextField(20);

        Form(JobRow existing, List<Choice<Integer>> envChoices, LocalDateTime now) {
            addField("Reservation number", reservation);
            addField("Environment", env);
            addField("Job name", name);
            addField("State", state);
            addField("Submitted (yyyy-mm-dd hh:mm)", submit);
            addField("Started (blank if not yet)", start);
            addField("Ended (blank if not yet)", end);
            addField("Exit code (if ended)", exitCode);
            addField("Peak VRAM in GB (optional)", peakVram);
            addField("GPU utilization in % (optional)", util);
            env.setChoices(envChoices);

            id = existing == null ? 0 : existing.job().jobId();
            if (existing == null) {
                submit.setText(Inputs.dateTimeText(now));
            } else {
                Job j = existing.job();
                reservation.setText(String.valueOf(j.reservationId()));
                env.select(j.envId());
                name.setText(j.jobName());
                state.setSelectedItem(j.jobState());
                submit.setText(Inputs.dateTimeText(j.submitTime()));
                start.setText(Inputs.dateTimeText(j.startTime()));
                end.setText(Inputs.dateTimeText(j.endTime()));
                exitCode.setText(j.exitCode() == null ? "" : j.exitCode().toString());
                peakVram.setText(j.peakVramGb() == null ? "" : j.peakVramGb().toPlainString());
                util.setText(j.avgGpuUtilPct() == null ? "" : j.avgGpuUtilPct().toPlainString());
            }
        }

        @Override
        protected JobRow read() {
            Job job = new Job(id,
                    Inputs.requiredInt("Reservation number", reservation, 1, Integer.MAX_VALUE),
                    Inputs.requiredChoice("Environment", env),
                    Inputs.requiredText("Job name", name, 100),
                    Inputs.requiredDateTime("Submitted", submit),
                    Inputs.optionalDateTime("Started", start),
                    Inputs.optionalDateTime("Ended", end),
                    (String) state.getSelectedItem(),
                    Inputs.optionalInt("Exit code", exitCode, Short.MIN_VALUE, Short.MAX_VALUE),
                    Inputs.optionalInRange("Peak VRAM", peakVram, BigDecimal.ZERO, MAX_VRAM, 1),
                    Inputs.optionalInRange("GPU utilization", util, BigDecimal.ZERO, HUNDRED, 2));
            checkRules(job);
            // The joined columns are not saved; the list is reloaded afterwards.
            return new JobRow(job, null, null, 0, null);
        }

        /** The rules of the CHECK constraints that involve more than one field. */
        private static void checkRules(Job j) {
            if (j.startTime() != null && j.startTime().isBefore(j.submitTime())) {
                throw new InputException(SqlErrors.rule("ck_job_start"));
            }
            if (j.endTime() != null && (j.startTime() == null || j.endTime().isBefore(j.startTime()))) {
                throw new InputException(SqlErrors.rule("ck_job_end"));
            }
            boolean started = j.startTime() != null;
            boolean ended = j.endTime() != null;
            boolean timesFitState = switch (j.jobState()) {
                case "QUEUED" -> !started && !ended;
                case "RUNNING" -> started && !ended;
                case "COMPLETED", "FAILED", "TIMEOUT" -> started && ended;
                default -> true; // CANCELLED: at any point
            };
            if (!timesFitState) {
                throw new InputException(SqlErrors.rule("ck_job_state_times"));
            }
            boolean exitCodeFitsState = switch (j.jobState()) {
                case "COMPLETED" -> j.exitCode() != null && j.exitCode() == 0;
                case "FAILED" -> j.exitCode() != null && j.exitCode() != 0;
                default -> true;
            };
            if (!exitCodeFitsState) {
                throw new InputException(SqlErrors.rule("ck_job_exit"));
            }
        }
    }
}
