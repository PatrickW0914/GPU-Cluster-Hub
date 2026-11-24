package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Membership;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import java.time.LocalDate;
import java.util.List;

/** Which users belong to which groups. */
public final class MembershipsPanel extends EntityPanel<Membership> {

    private static final List<Column<Membership>> COLUMNS = List.of(
            new Column<>("User id", Integer.class, Membership::userId),
            new Column<>("Group id", Integer.class, Membership::groupId),
            new Column<>("Member role", String.class, Membership::memberRole),
            new Column<>("Joined", LocalDate.class, Membership::joinedOn));

    private final FilterBox role = new FilterBox(Allowed.MEMBER_ROLES);

    public MembershipsPanel() {
        super("Membership", COLUMNS);
        addFilter("Member role", role);
    }

    @Override
    protected Transactions.Work<List<Membership>> search() {
        String roleValue = role.value();
        return c -> new GroupDao(c).searchMembers(null, null, roleValue);
    }
}
