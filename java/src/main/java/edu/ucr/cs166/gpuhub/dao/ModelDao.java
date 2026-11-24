package edu.ucr.cs166.gpuhub.dao;

import edu.ucr.cs166.gpuhub.entity.AiModel;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** SQL for ai_model. */
public final class ModelDao extends Dao {

    private static final String SELECT = """
            SELECT m.model_id, m.model_name, m.task_category, m.framework, m.license,
                   m.source_url, m.maintainer_id, m.description, m.added_on
              FROM ai_model m""";

    public ModelDao(Connection c) {
        super(c);
    }

    /** Inserts the model and returns the generated id. */
    public int insert(AiModel m) throws SQLException {
        return insertReturningId("""
                INSERT INTO ai_model (model_name, task_category, framework, license,
                                      source_url, maintainer_id, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING model_id""",
                m.modelName(), m.taskCategory(), m.framework(), m.license(),
                m.sourceUrl(), m.maintainerId(), m.description());
    }

    public Optional<AiModel> findById(int modelId) throws SQLException {
        return queryOne(SELECT + " WHERE m.model_id = ?", ModelDao::read, modelId);
    }

    /** Every filter may be null. text is looked for in the name and the description. */
    public List<AiModel> search(String text, String taskCategory, String framework, Integer maintainerId)
            throws SQLException {
        Filter f = new Filter()
                .andContains("(m.model_name ILIKE ? OR m.description ILIKE ?)", text)
                .and("m.task_category = ?", taskCategory)
                .and("m.framework = ?", framework)
                .and("m.maintainer_id = ?", maintainerId);
        return queryAll(SELECT + f.where() + " ORDER BY m.model_name", ModelDao::read, f.params());
    }

    /** Returns false if the model no longer exists. */
    public boolean update(AiModel m) throws SQLException {
        return execute("""
                UPDATE ai_model
                   SET model_name = ?, task_category = ?, framework = ?, license = ?,
                       source_url = ?, maintainer_id = ?, description = ?
                 WHERE model_id = ?""",
                m.modelName(), m.taskCategory(), m.framework(), m.license(),
                m.sourceUrl(), m.maintainerId(), m.description(),
                m.modelId()) == 1;
    }

    /** Deletes the model and (by cascade) its unused versions. Returns false if it no longer exists. */
    public boolean delete(int modelId) throws SQLException {
        return execute("DELETE FROM ai_model WHERE model_id = ?", modelId) == 1;
    }

    private static AiModel read(ResultSet rs) throws SQLException {
        return new AiModel(
                rs.getInt("model_id"), rs.getString("model_name"), rs.getString("task_category"),
                rs.getString("framework"), rs.getString("license"), rs.getString("source_url"),
                rs.getInt("maintainer_id"), rs.getString("description"),
                rs.getObject("added_on", LocalDate.class));
    }
}
