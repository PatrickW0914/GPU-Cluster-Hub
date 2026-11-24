package edu.ucr.cs166.gpuhub.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** The fields of an add/edit dialog, one labelled field per row. */
public abstract class EntityForm<T> extends JPanel {

    private int row = 0;

    protected EntityForm() {
        super(new GridBagLayout());
    }

    /** Checks the fields and returns the object they describe, or throws InputException. */
    protected abstract T read();

    protected void addField(String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row++;
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.LINE_END;
        add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(field, c);
    }
}
