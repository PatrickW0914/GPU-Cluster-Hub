package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.ClusterUser;
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
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** Cluster users. The group filter searches through the membership table. */
public final class UsersPanel extends EntityPanel<ClusterUser> implements Editor<ClusterUser> {

    private static final List<Column<ClusterUser>> COLUMNS = List.of(
            new Column<>("NetID", String.class, ClusterUser::netId),
            new Column<>("Name", String.class, ClusterUser::fullName),
            new Column<>("Email", String.class, ClusterUser::email),
            new Column<>("Role", String.class, ClusterUser::userRole),
            new Column<>("Admin", Boolean.class, ClusterUser::isAdmin),
            new Column<>("Active", Boolean.class, ClusterUser::isActive),
            new Column<>("Created", LocalDateTime.class, ClusterUser::createdAt));

    private final JTextField text = new JTextField(14);
    private final FilterBox role = new FilterBox(Allowed.USER_ROLES);
    private final FilterBox active = FilterBox.yesNo();
    private final ChoiceBox<Integer> group = new ChoiceBox<>("Any");

    public UsersPanel() {
        super("User", COLUMNS);
        addFilter("NetID, name or email", text);
        addFilter("Role", role);
        addFilter("Active", active);
        addFilter("Member of", group);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> groups = GroupsPanel.choices(new GroupDao(c), null);
            return () -> group.setChoices(groups);
        };
    }

    @Override
    protected Transactions.Work<List<ClusterUser>> search() {
        String searchText = text.getText();
        String roleValue = role.value();
        Boolean activeValue = active.booleanValue();
        Integer groupId = group.value();
        return c -> new UserDao(c).search(searchText, roleValue, groupId, activeValue);
    }

    /** Users as dropdown choices. role and active may be null (any). */
    static List<Choice<Integer>> choices(UserDao users, String role, Boolean active) throws SQLException {
        return users.search(null, role, null, active).stream()
                .map(u -> new Choice<>(u.userId(),
                        u.netId() + " - " + u.fullName() + (u.isActive() ? "" : " (inactive)")))
                .toList();
    }

    @Override
    public EntityForm<ClusterUser> form(ClusterUser existing) {
        return new Form(existing);
    }

    @Override
    public Transactions.Work<Boolean> insert(ClusterUser user) {
        return c -> new UserDao(c).insert(user) > 0; // an id was generated
    }

    @Override
    public Transactions.Work<Boolean> update(ClusterUser old, ClusterUser edited) {
        return c -> new UserDao(c).update(edited);
    }

    @Override
    public Transactions.Work<Boolean> delete(ClusterUser user) {
        return c -> new UserDao(c).delete(user.userId());
    }

    @Override
    public String nameOf(ClusterUser user) {
        return user.netId();
    }

    @Override
    public String deleteWarning(ClusterUser user) {
        return "The user's group memberships will be removed too.";
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "reservation" -> "This user has reservations, which are kept as history. "
                    + "Deactivate the user instead: Edit, then untick Active.";
            case "research_group" -> "This user is the PI of a group. "
                    + "Choose another PI first: Research groups, Edit.";
            case "ai_model" -> "This user maintains models. Give them another maintainer first, "
                    + "or deactivate the user: Edit, then untick Active.";
            default -> null;
        };
    }

    private static final class Form extends EntityForm<ClusterUser> {

        private final ClusterUser existing; // null for a new user
        private final JTextField netId = new JTextField(20);
        private final JTextField fullName = new JTextField(20);
        private final JTextField email = new JTextField(20);
        private final JComboBox<String> role = new JComboBox<>(Allowed.USER_ROLES.toArray(new String[0]));
        private final JCheckBox admin = new JCheckBox("administrator");
        private final JCheckBox active = new JCheckBox("may use the cluster", true);

        Form(ClusterUser existing) {
            this.existing = existing;
            addField("NetID", netId);
            addField("Full name", fullName);
            addField("Email", email);
            addField("Role", role);
            addField("Admin", admin);
            addField("Active", active);
            if (existing == null) {
                role.setSelectedItem("PhD"); // the most common role
            } else {
                netId.setText(existing.netId());
                fullName.setText(existing.fullName());
                email.setText(existing.email());
                role.setSelectedItem(existing.userRole());
                admin.setSelected(existing.isAdmin());
                active.setSelected(existing.isActive());
            }
        }

        @Override
        protected ClusterUser read() {
            return new ClusterUser(
                    existing == null ? 0 : existing.userId(),
                    // the same patterns as ck_user_netid and ck_user_email
                    Inputs.requiredMatch("NetID", netId, 20, "[a-z][a-z0-9]{2,19}",
                            SqlErrors.rule("ck_user_netid")),
                    Inputs.requiredText("Full name", fullName, 80),
                    Inputs.requiredMatch("Email", email, 120, "[^@\\s]+@[^@\\s]+\\.[a-z]{2,}",
                            SqlErrors.rule("ck_user_email")),
                    (String) role.getSelectedItem(),
                    admin.isSelected(),
                    active.isSelected(),
                    existing == null ? null : existing.createdAt());
        }
    }
}
