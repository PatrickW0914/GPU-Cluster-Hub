package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.GroupRow;
import edu.ucr.cs166.gpuhub.entity.ResearchGroup;
import edu.ucr.cs166.gpuhub.service.GroupService;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.InputException;
import edu.ucr.cs166.gpuhub.ui.Inputs;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/** Research groups. The member filter searches through the membership and user tables. */
public final class GroupsPanel extends EntityPanel<GroupRow> implements Editor<GroupRow> {

    private static final List<Column<GroupRow>> COLUMNS = List.of(
            new Column<>("Group", String.class, r -> r.group().groupName()),
            new Column<>("Department", String.class, r -> r.group().department()),
            new Column<>("PI", String.class, GroupRow::piName),
            new Column<>("Weekly GPU-hour quota", BigDecimal.class, r -> r.group().weeklyGpuHourQuota()),
            new Column<>("Members", Integer.class, GroupRow::memberCount),
            new Column<>("Created", LocalDate.class, r -> r.group().createdOn()));

    private final JTextField name = new JTextField(12);
    private final JTextField department = new JTextField(12);
    private final JTextField member = new JTextField(12);

    private List<Choice<Integer>> facultyChoices = List.of(); // for the form's PI dropdown

    public GroupsPanel() {
        super("Research group", COLUMNS);
        addFilter("Group name", name);
        addFilter("Department", department);
        addFilter("Has member (NetID or name)", member);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> faculty = UsersPanel.choices(new UserDao(c), "Faculty", null);
            return () -> facultyChoices = faculty;
        };
    }

    @Override
    protected Transactions.Work<List<GroupRow>> search() {
        String nameText = name.getText();
        String departmentText = department.getText();
        String memberText = member.getText();
        return c -> new GroupDao(c).search(nameText, departmentText, memberText, null);
    }

    /** Groups as dropdown choices: all of them, or only those of one member. */
    static List<Choice<Integer>> choices(GroupDao groups, Integer memberUserId) throws SQLException {
        return groups.search(null, null, null, memberUserId).stream()
                .map(r -> new Choice<>(r.group().groupId(), r.group().groupName()))
                .toList();
    }

    @Override
    public EntityForm<GroupRow> form(GroupRow existing) {
        return new Form(existing, facultyChoices);
    }

    /** The group and its PI's membership are saved in one transaction. */
    @Override
    public Transactions.Work<Boolean> insert(GroupRow row) {
        return c -> GroupService.create(c, row.group()) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(GroupRow old, GroupRow edited) {
        return c -> new GroupDao(c).update(edited.group());
    }

    @Override
    public Transactions.Work<Boolean> delete(GroupRow row) {
        return c -> new GroupDao(c).delete(row.group().groupId());
    }

    @Override
    public String nameOf(GroupRow row) {
        return row.group().groupName();
    }

    @Override
    public String deleteWarning(GroupRow row) {
        return "Its memberships (" + row.memberCount() + ") will be removed too.";
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "reservation" -> "This group has reservations, which are kept for the usage reports, "
                    + "so it can't be deleted.";
            default -> null;
        };
    }

    private static final class Form extends EntityForm<GroupRow> {

        private static final BigDecimal MAX_QUOTA = new BigDecimal("10000"); // ck_group_quota

        private final GroupRow existing; // null for a new group
        private final JTextField name = new JTextField(22);
        private final JTextField department = new JTextField(22);
        private final ChoiceBox<Integer> pi = new ChoiceBox<>("Select a Faculty user");
        private final JTextField quota = new JTextField(22);

        Form(GroupRow existing, List<Choice<Integer>> facultyChoices) {
            this.existing = existing;
            addField("Group name", name);
            addField("Department", department);
            addField("PI", pi);
            addField("Weekly GPU-hour quota", quota);
            pi.setChoices(facultyChoices);
            if (existing != null) {
                name.setText(existing.group().groupName());
                department.setText(existing.group().department());
                pi.select(existing.group().piUserId());
                quota.setText(existing.group().weeklyGpuHourQuota().toPlainString());
                pi.setToolTipText("A new PI must already be a member of the group.");
            }
        }

        @Override
        protected GroupRow read() {
            String nameText = Inputs.requiredText("Group name", name, 80);
            String departmentText = Inputs.requiredText("Department", department, 60);
            int piUserId = Inputs.requiredChoice("PI", pi);
            BigDecimal quotaValue = Inputs.requiredPositive("Weekly GPU-hour quota", quota, 7, 1);
            if (quotaValue.compareTo(MAX_QUOTA) > 0) {
                throw new InputException(SqlErrors.rule("ck_group_quota"));
            }
            ResearchGroup group = existing == null
                    ? new ResearchGroup(0, nameText, departmentText, piUserId, quotaValue, null)
                    : new ResearchGroup(existing.group().groupId(), nameText, departmentText, piUserId,
                            quotaValue, existing.group().createdOn());
            // The joined columns are not saved; the list is reloaded afterwards.
            return new GroupRow(group, null, 0);
        }
    }
}
