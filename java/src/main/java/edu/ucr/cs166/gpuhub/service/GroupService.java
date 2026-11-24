package edu.ucr.cs166.gpuhub.service;

import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Membership;
import edu.ucr.cs166.gpuhub.entity.ResearchGroup;

import java.sql.SQLException;

/** Group operations that need more than one statement. */
public final class GroupService {

    private GroupService() { }

    /**
     * Creates a group and its PI's membership in one transaction, and returns the new id.
     *
     * The two rows reference each other (the group's PI must be a member of it, and a
     * membership needs its group), so neither can be saved first. The PI foreign key is
     * DEFERRED: PostgreSQL checks it at commit, when both rows exist.
     */
    public static int create(ResearchGroup group) throws SQLException {
        return Transactions.run(c -> {
            GroupDao groups = new GroupDao(c);
            int groupId = groups.insert(group);
            groups.addMember(new Membership(group.piUserId(), groupId, "Manager", null));
            return groupId;
        });
    }
}
