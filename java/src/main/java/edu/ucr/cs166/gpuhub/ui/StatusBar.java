package edu.ucr.cs166.gpuhub.ui;

import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.service.AppClock;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.time.format.DateTimeFormatter;

/** The bottom line of the window: connection status on the left, the app's current time on the right. */
public final class StatusBar extends JPanel {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int CHECK_EVERY_MS = 10_000;

    private final AppClock clock;
    private final JLabel connection = new JLabel("● Connecting...");
    private final JLabel time = new JLabel();
    private boolean checking = false;

    public StatusBar(AppClock clock) {
        super(new BorderLayout());
        this.clock = clock;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(2, 12, 2, 12)));

        JCheckBox useReal = new JCheckBox("Use real time");
        useReal.addActionListener(e -> {
            clock.setReal(useReal.isSelected());
            showTime();
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        right.add(time);
        right.add(useReal);
        add(connection, BorderLayout.WEST);
        add(right, BorderLayout.EAST);

        showTime();
        new Timer(1_000, e -> showTime()).start(); // keeps the real time up to date
        checkConnection();
        new Timer(CHECK_EVERY_MS, e -> checkConnection()).start();
    }

    private void showTime() {
        time.setText("Current time: " + clock.now().format(FORMAT)
                + (clock.isReal() ? " (real)" : " (demo snapshot)"));
    }

    private void checkConnection() {
        if (checking) {
            return; // the last check is still waiting for an answer
        }
        checking = true;
        // Opening the connection is the check; its metadata says what we are connected to.
        DbTask.run(c -> c.getCatalog() + " as " + c.getMetaData().getUserName(),
                where -> showConnection("● Connected to " + where, Messages.SUCCESS_COLOR, null),
                error -> showConnection("● Not connected", Messages.ERROR_COLOR,
                        SqlErrors.message(error) + " " + SqlErrors.detail(error)));
    }

    private void showConnection(String text, Color color, String toolTip) {
        checking = false;
        connection.setText(text);
        connection.setForeground(color);
        connection.setToolTipText(toolTip);
    }
}
