package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.ModelVersion;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Model versions (checkpoints). */
public final class VersionsPanel extends EntityPanel<ModelVersion> {

    private static final List<Column<ModelVersion>> COLUMNS = List.of(
            new Column<>("Model id", Integer.class, ModelVersion::modelId),
            new Column<>("Version", String.class, ModelVersion::versionTag),
            new Column<>("Parameters (B)", BigDecimal.class, ModelVersion::paramCountB),
            new Column<>("Precision", String.class, ModelVersion::weightPrecision),
            new Column<>("Min VRAM (GB)", BigDecimal.class, ModelVersion::minVramGb),
            new Column<>("Size (GB)", BigDecimal.class, ModelVersion::checkpointSizeGb),
            new Column<>("Released", LocalDate.class, ModelVersion::releasedOn),
            new Column<>("Deprecated", Boolean.class, ModelVersion::isDeprecated));

    private final FilterBox precision = new FilterBox(Allowed.PRECISIONS);
    private final FilterBox deprecated = FilterBox.yesNo();

    public VersionsPanel() {
        super("Model version", COLUMNS);
        addFilter("Precision", precision);
        addFilter("Deprecated", deprecated);
    }

    @Override
    protected Transactions.Work<List<ModelVersion>> search() {
        String precisionValue = precision.value();
        Boolean deprecatedValue = deprecated.booleanValue();
        return c -> new ModelVersionDao(c).search(null, precisionValue, null, deprecatedValue);
    }
}
