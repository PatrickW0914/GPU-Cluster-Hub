package edu.ucr.cs166.gpuhub.ui;

import edu.ucr.cs166.gpuhub.entity.Choice;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.util.List;

/**
 * A dropdown whose choices come from the database. Its first item means "nothing chosen".
 *
 * @param <K> the key type of the rows to choose from
 */
public final class ChoiceBox<K> extends JComboBox<Choice<K>> {

    private final Choice<K> none;

    /** @param noneLabel text of the first item, such as "Any" for a filter */
    public ChoiceBox(String noneLabel) {
        none = new Choice<>(null, noneLabel);
        addItem(none);
        // Show names from the database as plain text, even if they start with "<html>".
        ((JComponent) getRenderer()).putClientProperty("html.disable", Boolean.TRUE);
    }

    /** Replaces the choices and keeps the current one selected if it still exists. */
    public void setChoices(List<Choice<K>> choices) {
        K selected = value();
        removeAllItems();
        addItem(none);
        choices.forEach(this::addItem);
        if (selected != null) {
            select(selected);
        }
    }

    /** The key of the selected choice, or null if nothing is chosen. */
    public K value() {
        int index = getSelectedIndex();
        return index < 0 ? null : getItemAt(index).key();
    }

    public void select(K key) {
        for (int i = 0; i < getItemCount(); i++) {
            if (key.equals(getItemAt(i).key())) {
                setSelectedIndex(i);
                return;
            }
        }
        setSelectedIndex(0); // it no longer exists
    }
}
