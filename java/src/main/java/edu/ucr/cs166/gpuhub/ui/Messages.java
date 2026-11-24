package edu.ucr.cs166.gpuhub.ui;

import edu.ucr.cs166.gpuhub.db.SqlErrors;

import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.sql.SQLException;

/** The dialogs used by every screen. */
public final class Messages {

    public static final Color SUCCESS_COLOR = new Color(0x1B7F3B);
    public static final Color ERROR_COLOR = new Color(0xB3261E);

    private Messages() { }

    /** A database error: the friendly message in bold, the database's own text below it. */
    public static void databaseError(Component parent, SQLException e) {
        JTextArea message = wrappedText(SqlErrors.message(e));
        message.setFont(message.getFont().deriveFont(Font.BOLD));
        JTextArea detail = wrappedText(SqlErrors.detail(e));
        JOptionPane.showMessageDialog(parent, new Object[] {message, detail},
                "Database error", JOptionPane.ERROR_MESSAGE);
    }

    /** Input that was rejected before reaching the database. */
    public static void invalidInput(Component parent, InputException e) {
        JOptionPane.showMessageDialog(parent, wrappedText(e.getMessage()),
                "Please check your input", JOptionPane.WARNING_MESSAGE);
    }

    /** Asks before deleting. Cancel is the default, so pressing Enter does not delete. */
    public static boolean confirmDelete(Component parent, String what) {
        String[] options = {"Delete", "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, wrappedText("Delete " + what + "?"),
                "Confirm delete", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        return choice == 0;
    }

    /** Read-only text that wraps. Unlike a JLabel, it never interprets its text as HTML. */
    private static JTextArea wrappedText(String text) {
        JTextArea area = new JTextArea(text);
        area.setColumns(38);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        area.setSize(area.getPreferredSize()); // lets the wrapped height be computed
        return area;
    }
}
