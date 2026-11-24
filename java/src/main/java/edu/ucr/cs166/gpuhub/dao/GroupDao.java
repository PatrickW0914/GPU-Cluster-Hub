package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.GroupRow;
import edu.ucr.cs166.gpuhub.entity.Membership;
import edu.ucr.cs166.gpuhub.entity.MembershipRow;
import edu.ucr.cs166.gpuhub.entity.ResearchGroup;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** SQL for research_group and membership. */
public final class GroupDao extends Dao {

    private static final String SELECT = """
            SELECT g.group_id, g.group_name, g.department, g.pi_user_id,
                   g.weekly_gpu_hour_quota, g.created_on
              FROM research_group g""";

    /** For lists: adds the PI's name and the number of members. */
    private static final String SELECT_ROW = """
            SELECT g.group_id, g.group_name, g.department, g.pi_user_id,
                   g.weekly_gpu_hour_quota, g.created_on, pi.full_name AS pi_name,
                   (SELECT COUNT(*) FROM membership m WHERE m.group_id = g.group_id) AS member_count
              FROM research_group g
              JOIN cluster_user pi ON pi.user_id = g.pi_user_id""";

    private static final String SELECT_MEMBER = """
            SELECT m.user_id, m.group_id, m.member_role, m.joined_on
              FROM membership m""";

    /** For lists: adds the names, and whether this member is the group's PI. */
    private static final String SELECT_MEMBER_ROW = """
            SELECT m.user_id, m.group_id, m.member_role, m.joined_on,
                   u.net_id, u.full_name, g.group_name, (g.pi_user_id = m.user_id) AS is_pi
              FROM membership m
              JOIN cluster_user u   ON u.user_id = m.user_id
              JOIN research_group g ON g.group_id = m.group_id""";

    public GroupDao(Connection c) {
        super(c);
    }

    // ---------------------------------------------------------------- groups

    /**
     * Inserts only the group row and returns the generated id. The PI's membership must be
     * added in the same transaction, or the commit fails; use GroupService.create.
     */
    public int insert(ResearchGroup g) throws SQLException {
        return insertReturningId("""
                INSERT INTO research_group (group_name, department, pi_user_id, weekly_gpu_hour_quota)
                VALUES (?, ?, ?, ?)
                RETURNING group_id""",
                g.groupName(), g.department(), g.piUserId(), g.weeklyGpuHourQuota());
    }

    public Optional<ResearchGroup> findById(int groupId) throws SQLException {
        return queryOne(SELECT + " WHERE g.group_id = ?", GroupDao::read, groupId);
    }

    /**
     * Every filter may be null. memberText finds the groups with a member of that NetID or
     * name; memberUserId finds the groups one user belongs to.
     */
    public List<GroupRow> search(String text, String department, String memberText, Integer memberUserId)
            throws SQLException {
        Filter f = new Filter()
                .andContains("g.group_name ILIKE ?", text)
                .andContains("g.department ILIKE ?", department)
                .andContains("""
                        EXISTS (SELECT 1 FROM membership m JOIN cluster_user u ON u.user_id = m.user_id
                                 WHERE m.group_id = g.group_id
                                   AND (u.net_id ILIKE ? OR u.full_name ILIKE ?))""", memberText)
                .and("EXISTS (SELECT 1 FROM membership m WHERE m.group_id = g.group_id AND m.user_id = ?)",
                        memberUserId);
        return queryAll(SELECT_ROW + f.where() + " ORDER BY g.group_name", GroupDao::readRow, f.params());
    }

    /** Returns false if the group no longer exists. A new PI must already be a member. */
    public boolean update(ResearchGroup g) throws SQLException {
        return execute("""
                UPDATE research_group
                   SET group_name = ?, department = ?, pi_user_id = ?, weekly_gpu_hour_quota = ?
                 WHERE group_id = ?""",
                g.groupName(), g.department(), g.piUserId(), g.weeklyGpuHourQuota(),
                g.groupId()) == 1;
    }

    /** Deletes the group and (by cascade) its memberships. Returns false if it no longer exists. */
    public boolean delete(int groupId) throws SQLException {
        return execute("DELETE FROM research_group WHERE group_id = ?", groupId) == 1;
    }

    // ----------------------------------------------------------- memberships
    // The key (user_id, group_id) is chosen by the caller, so there is no id to return.

    public void addMember(Membership m) throws SQLException {
        execute("INSERT INTO membership (user_id, group_id, member_role) VALUES (?, ?, ?)",
                m.userId(), m.groupId(), m.memberRole());
    }

    public Optional<Membership> findMember(int userId, int groupId) throws SQLException {
        return queryOne(SELECT_MEMBER + " WHERE m.user_id = ? AND m.group_id = ?",
                GroupDao::readMember, userId, groupId);
    }

    /** Every filter may be null. userText is looked for in NetID and name. */
    public List<MembershipRow> searchMembers(String userText, Integer groupId, String memberRole)
            throws SQLException {
        Filter f = new Filter()
                .andContains("(u.net_id ILIKE ? OR u.full_name ILIKE ?)", userText)
                .and("m.group_id = ?", groupId)
                .and("m.member_role = ?", memberRole);
        return queryAll(SELECT_MEMBER_ROW + f.where() + " ORDER BY g.group_name, u.net_id",
                GroupDao::readMemberRow, f.params());
    }

    /** Changes the member's role. Returns false if the membership no longer exists. */
    public boolean updateMember(Membership m) throws SQLException {
        return execute("UPDATE membership SET member_role = ? WHERE user_id = ? AND group_id = ?",
                m.memberRole(), m.userId(), m.groupId()) == 1;
    }

    /** Returns false if the membership no longer exists. */
    public boolean removeMember(int userId, int groupId) throws SQLException {
        return execute("DELETE FROM membership WHERE user_id = ? AND group_id = ?", userId, groupId) == 1;
    }

    private static ResearchGroup read(ResultSet rs) throws SQLException {
        return new ResearchGroup(
                rs.getInt("group_id"), rs.getString("group_name"), rs.getString("department"),
                rs.getInt("pi_user_id"), rs.getBigDecimal("weekly_gpu_hour_quota"),
                rs.getObject("created_on", LocalDate.class));
    }

    private static GroupRow readRow(ResultSet rs) throws SQLException {
        return new GroupRow(read(rs), rs.getString("pi_name"), rs.getInt("member_count"));
    }

    private static MembershipRow readMemberRow(ResultSet rs) throws SQLException {
        return new MembershipRow(readMember(rs), rs.getString("net_id"), rs.getString("full_name"),
                rs.getString("group_name"), rs.getBoolean("is_pi"));
    }

    private static Membership readMember(ResultSet rs) throws SQLException {
        return new Membership(
                rs.getInt("user_id"), rs.getInt("group_id"), rs.getString("member_role"),
                rs.getObject("joined_on", LocalDate.class));
    }
}
