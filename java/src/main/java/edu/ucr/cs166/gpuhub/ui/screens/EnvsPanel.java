package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.EnvDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.SoftwareEnv;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Software environments. */
public final class EnvsPanel extends EntityPanel<SoftwareEnv> {

    private static final List<Column<SoftwareEnv>> COLUMNS = List.of(
            new Column<>("Environment", String.class, SoftwareEnv::envName),
            new Column<>("Python", String.class, SoftwareEnv::pythonVersion),
            new Column<>("CUDA", BigDecimal.class, SoftwareEnv::cudaVersion),
            new Column<>("Framework", String.class, SoftwareEnv::framework),
            new Column<>("Framework version", String.class, SoftwareEnv::frameworkVersion),
            new Column<>("Container image", String.class, SoftwareEnv::containerImage),
            new Column<>("Created", LocalDate.class, SoftwareEnv::createdOn));

    private final JTextField name = new JTextField(14);
    private final FilterBox framework = new FilterBox(Allowed.FRAMEWORKS);

    public EnvsPanel() {
        super("Environment", COLUMNS);
        addFilter("Name", name);
        addFilter("Framework", framework);
    }

    @Override
    protected Transactions.Work<List<SoftwareEnv>> search() {
        String nameText = name.getText();
        String frameworkValue = framework.value();
        return c -> new EnvDao(c).search(nameText, frameworkValue, null);
    }
}
