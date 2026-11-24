package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.ResearchGroup;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Research groups and their weekly quotas. */
public final class GroupsPanel extends EntityPanel<ResearchGroup> {

    private static final List<Column<ResearchGroup>> COLUMNS = List.of(
            new Column<>("Group", String.class, ResearchGroup::groupName),
            new Column<>("Department", String.class, ResearchGroup::department),
            new Column<>("PI user id", Integer.class, ResearchGroup::piUserId),
            new Column<>("Weekly GPU-hour quota", BigDecimal.class, ResearchGroup::weeklyGpuHourQuota),
            new Column<>("Created", LocalDate.class, ResearchGroup::createdOn));

    private final JTextField name = new JTextField(14);
    private final JTextField department = new JTextField(14);

    public GroupsPanel() {
        super("Research group", COLUMNS);
        addFilter("Group name", name);
        addFilter("Department", department);
    }

    @Override
    protected Transactions.Work<List<ResearchGroup>> search() {
        String nameText = name.getText();
        String departmentText = department.getText();
        return c -> new GroupDao(c).search(nameText, departmentText, null);
    }
}
