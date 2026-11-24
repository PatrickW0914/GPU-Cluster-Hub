package edu.ucr.cs166.gpuhub.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagLayout;

/** Stands in for a screen that is not built yet. */
final class PlaceholderPanel extends JPanel {

    PlaceholderPanel(String text) {
        super(new GridBagLayout()); // centers its only component
        add(new JLabel(text));
    }
}
