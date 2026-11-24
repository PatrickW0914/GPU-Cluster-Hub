package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.JobDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Job;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Jobs run inside reservations. */
public final class JobsPanel extends EntityPanel<Job> {

    private static final List<Column<Job>> COLUMNS = List.of(
            new Column<>("Job", Long.class, Job::jobId),
            new Column<>("Reservation", Integer.class, Job::reservationId),
            new Column<>("Env id", Integer.class, Job::envId),
            new Column<>("Name", String.class, Job::jobName),
            new Column<>("Submitted", LocalDateTime.class, Job::submitTime),
            new Column<>("Started", LocalDateTime.class, Job::startTime),
            new Column<>("Ended", LocalDateTime.class, Job::endTime),
            new Column<>("State", String.class, Job::jobState),
            new Column<>("Exit code", Integer.class, Job::exitCode),
            new Column<>("Peak VRAM (GB)", BigDecimal.class, Job::peakVramGb),
            new Column<>("GPU util (%)", BigDecimal.class, Job::avgGpuUtilPct));

    private final JTextField name = new JTextField(14);
    private final FilterBox state = new FilterBox(Allowed.JOB_STATES);

    public JobsPanel() {
        super("Job", COLUMNS);
        addFilter("Job name", name);
        addFilter("State", state);
    }

    @Override
    protected Transactions.Work<List<Job>> search() {
        String nameText = name.getText();
        String stateValue = state.value();
        return c -> new JobDao(c).search(nameText, stateValue, null, null, null);
    }
}
