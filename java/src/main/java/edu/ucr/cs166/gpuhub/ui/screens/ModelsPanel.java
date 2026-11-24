package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.ModelDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.AiModel;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import javax.swing.JTextField;
import java.time.LocalDate;
import java.util.List;

/** AI models. */
public final class ModelsPanel extends EntityPanel<AiModel> {

    private static final List<Column<AiModel>> COLUMNS = List.of(
            new Column<>("Model", String.class, AiModel::modelName),
            new Column<>("Task", String.class, AiModel::taskCategory),
            new Column<>("Framework", String.class, AiModel::framework),
            new Column<>("License", String.class, AiModel::license),
            new Column<>("Source", String.class, AiModel::sourceUrl),
            new Column<>("Description", String.class, AiModel::description),
            new Column<>("Added", LocalDate.class, AiModel::addedOn));

    private final JTextField text = new JTextField(14);
    private final FilterBox task = new FilterBox(Allowed.TASKS);
    private final FilterBox framework = new FilterBox(Allowed.FRAMEWORKS);

    public ModelsPanel() {
        super("Model", COLUMNS);
        addFilter("Name or description", text);
        addFilter("Task", task);
        addFilter("Framework", framework);
    }

    @Override
    protected Transactions.Work<List<AiModel>> search() {
        String searchText = text.getText();
        String taskValue = task.value();
        String frameworkValue = framework.value();
        return c -> new ModelDao(c).search(searchText, taskValue, frameworkValue, null);
    }
}
