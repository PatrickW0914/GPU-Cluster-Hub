package edu.ucr.cs166.gpuhub.ui;

import javax.swing.JComboBox;
import java.util.List;

/** A dropdown for a search filter. Its first choice, "Any", means no filter. */
public final class FilterBox extends JComboBox<String> {

    public FilterBox(List<String> values) {
        addItem("Any");
        values.forEach(this::addItem);
    }

    public static FilterBox yesNo() {
        return new FilterBox(List.of("Yes", "No"));
    }

    /** The chosen value, or null for "Any". */
    public String value() {
        return getSelectedIndex() <= 0 ? null : (String) getSelectedItem();
    }

    /** For a yesNo() box: true, false, or null for "Any". */
    public Boolean booleanValue() {
        return value() == null ? null : value().equals("Yes");
    }
}
