package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.ClusterUser;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** SQL for cluster_user. */
public final class UserDao extends Dao {

    private static final String SELECT = """
            SELECT u.user_id, u.net_id, u.full_name, u.email, u.user_role,
                   u.is_admin, u.is_active, u.created_at
              FROM cluster_user u""";

    public UserDao(Connection c) {
        super(c);
    }

    /** Inserts the user and returns the generated id. */
    public int insert(ClusterUser u) throws SQLException {
        return insertReturningId("""
                INSERT INTO cluster_user (net_id, full_name, email, user_role, is_admin, is_active)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING user_id""",
                u.netId(), u.fullName(), u.email(), u.userRole(), u.isAdmin(), u.isActive());
    }

    public Optional<ClusterUser> findById(int userId) throws SQLException {
        return queryOne(SELECT + " WHERE u.user_id = ?", UserDao::read, userId);
    }

    /** Every filter may be null. text is looked for in NetID, name and email. */
    public List<ClusterUser> search(String text, String role, Integer groupId, Boolean active)
            throws SQLException {
        Filter f = new Filter()
                .andContains("(u.net_id ILIKE ? OR u.full_name ILIKE ? OR u.email ILIKE ?)", text)
                .and("u.user_role = ?", role)
                .and("EXISTS (SELECT 1 FROM membership m WHERE m.user_id = u.user_id AND m.group_id = ?)", groupId)
                .and("u.is_active = ?", active);
        return queryAll(SELECT + f.where() + " ORDER BY u.net_id", UserDao::read, f.params());
    }

    /** Returns false if the user no longer exists. */
    public boolean update(ClusterUser u) throws SQLException {
        return execute("""
                UPDATE cluster_user
                   SET net_id = ?, full_name = ?, email = ?, user_role = ?, is_admin = ?, is_active = ?
                 WHERE user_id = ?""",
                u.netId(), u.fullName(), u.email(), u.userRole(), u.isAdmin(), u.isActive(),
                u.userId()) == 1;
    }

    /** Returns false if the user no longer exists. */
    public boolean delete(int userId) throws SQLException {
        return execute("DELETE FROM cluster_user WHERE user_id = ?", userId) == 1;
    }

    private static ClusterUser read(ResultSet rs) throws SQLException {
        return new ClusterUser(
                rs.getInt("user_id"), rs.getString("net_id"), rs.getString("full_name"),
                rs.getString("email"), rs.getString("user_role"),
                rs.getBoolean("is_admin"), rs.getBoolean("is_active"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
