package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.ClusterUser;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import javax.swing.JTextField;
import java.time.LocalDateTime;
import java.util.List;

/** Cluster users. */
public final class UsersPanel extends EntityPanel<ClusterUser> {

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

    public UsersPanel() {
        super("User", COLUMNS);
        addFilter("NetID, name or email", text);
        addFilter("Role", role);
        addFilter("Active", active);
    }

    @Override
    protected Transactions.Work<List<ClusterUser>> search() {
        String searchText = text.getText();
        String roleValue = role.value();
        Boolean activeValue = active.booleanValue();
        return c -> new UserDao(c).search(searchText, roleValue, null, activeValue);
    }
}
