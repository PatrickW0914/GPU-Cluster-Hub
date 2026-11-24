package edu.ucr.cs166.gpuhub;

import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Starts the application. */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                // not important: Swing keeps its default look
            }
            new MainWindow(new AppClock()).setVisible(true);
        });
    }
}
