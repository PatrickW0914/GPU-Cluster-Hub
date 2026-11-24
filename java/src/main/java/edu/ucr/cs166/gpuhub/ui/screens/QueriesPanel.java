package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.ModelVersionDao;
import edu.ucr.cs166.gpuhub.dao.ReportDao;
import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.entity.ModelName;
import edu.ucr.cs166.gpuhub.entity.Report;
import edu.ucr.cs166.gpuhub.entity.ReportColumn;
import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.service.Reports;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.DbTask;
import edu.ucr.cs166.gpuhub.ui.InputException;
import edu.ucr.cs166.gpuhub.ui.Inputs;
import edu.ucr.cs166.gpuhub.ui.Messages;
import edu.ucr.cs166.gpuhub.ui.ResultsTable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Runs the queries Q1-Q8: the list on the left, then what it answers, its inputs and its result. */
public final class QueriesPanel extends JPanel {

    private static final ModelName DEFAULT_MODEL = new ModelName("Llama-3.1-8B-Instruct", "bf16");

    private final AppClock clock;
    private final JList<Report> list = new JList<>(Reports.ALL.toArray(new Report[0]));
    private final JLabel answers = new JLabel(" ");
    private final JLabel concept = new JLabel(" ");
    private final ChoiceBox<ModelName> model = new ChoiceBox<>("Select a model version");
    private final JTextField hours = new JTextField("6", 4);
    private final JTextField now = new JTextField(12);
    private final JButton clockButton = new JButton("Use app time");
    private final JButton runButton = new JButton("Run");
    private final JButton sqlButton = new JButton("Show SQL");
    private final JPanel modelPair = pair("Model version", model);
    private final JPanel hoursPair = pair("Hours", hours);
    private final JScrollPane resultArea = new JScrollPane();
    private final JLabel message = new JLabel(" ");

    private int latestRun = 0;
    private String filledIn = ""; // the time the app put into the field, to notice if the user changed it

    public QueriesPanel(AppClock clock) {
        super(new BorderLayout(12, 0));
        this.clock = clock;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(32);
        list.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        add(list, BorderLayout.WEST);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        JPanel description = new JPanel(new GridLayout(2, 1, 0, 2));
        answers.setFont(answers.getFont().deriveFont(Font.BOLD));
        description.add(answers);
        description.add(concept);
        top.add(description, BorderLayout.NORTH);
        JPanel inputs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        inputs.add(modelPair);
        inputs.add(hoursPair);
        JPanel timeAndButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        timeAndButtons.add(pair("Current time (yyyy-mm-dd hh:mm)", now));
        timeAndButtons.add(clockButton);
        timeAndButtons.add(runButton);
        timeAndButtons.add(sqlButton);
        JPanel rows = new JPanel(new GridLayout(2, 1));
        rows.add(inputs);
        rows.add(timeAndButtons);
        top.add(rows, BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(top, BorderLayout.NORTH);
        center.add(resultArea, BorderLayout.CENTER);
        center.add(message, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showReport();
            }
        });
        runButton.addActionListener(e -> run());
        sqlButton.addActionListener(e -> showSql());
        clockButton.addActionListener(e -> fillInTime());
        hours.addActionListener(e -> run()); // Enter
        now.addActionListener(e -> run());
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                if (now.getText().equals(filledIn)) { // keep a time typed by the user
                    fillInTime();
                }
                loadModels();
            }
        });
        list.setSelectedIndex(0);
    }

    private static JPanel pair(String label, JComponent field) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.add(new JLabel(label));
        panel.add(field);
        return panel;
    }

    private void fillInTime() {
        filledIn = Inputs.dateTimeText(clock.now());
        now.setText(filledIn);
    }

    private void loadModels() {
        DbTask.run(c -> VersionsPanel.nameChoices(new ModelVersionDao(c)),
                models -> {
                    boolean firstTime = model.getItemCount() == 1;
                    model.setChoices(models);
                    if (firstTime) {
                        model.select(DEFAULT_MODEL);
                    }
                },
                error -> showMessage(SqlErrors.message(error), false));
    }

    /** Shows the description, the inputs this query needs, and an empty result. */
    private void showReport() {
        Report report = list.getSelectedValue();
        answers.setText(report.id() + ": " + report.answers());
        concept.setText("SQL concept: " + report.concept());
        modelPair.setVisible(report.needsModel());
        hoursPair.setVisible(report.needsHours());
        latestRun++; // a query that is still running no longer belongs here
        showRows(report, List.of());
        showMessage(" ", true);
        runButton.setEnabled(true);
    }

    private void run() {
        Report report = list.getSelectedValue();
        Map<String, Object> parameters = new HashMap<>();
        try {
            parameters.put("now", Inputs.requiredDateTime("Current time", now));
            if (report.needsModel()) {
                ModelName name = Inputs.requiredChoice("Model version", model);
                parameters.put("model", name.modelName());
                parameters.put("version", name.versionTag());
            }
            if (report.needsHours()) {
                parameters.put("hours", Inputs.requiredInt("Hours", hours, 1, 72)); // ck_res_maxlen
            }
        } catch (InputException e) {
            Messages.invalidInput(this, e);
            return;
        }
        int thisRun = ++latestRun;
        long startNanos = System.nanoTime();
        runButton.setEnabled(false);
        showMessage("Running " + report.id() + " ...", true);
        DbTask.run(c -> new ReportDao(c).run(report, parameters),
                rows -> {
                    if (thisRun == latestRun) {
                        long millis = (System.nanoTime() - startNanos) / 1_000_000;
                        runButton.setEnabled(true);
                        showRows(report, rows);
                        showMessage((rows.size() == 1 ? "1 row" : rows.size() + " rows") + " in " + millis
                                + " ms. Click a column title to sort.", true);
                    }
                },
                error -> {
                    if (thisRun == latestRun) {
                        runButton.setEnabled(true);
                        showMessage(SqlErrors.message(error), false);
                        Messages.databaseError(this, error);
                    }
                });
    }

    /** Every query has its own columns, so each result gets a new table. */
    private void showRows(Report report, List<List<Object>> rows) {
        List<Column<List<Object>>> columns = new ArrayList<>();
        for (int i = 0; i < report.columns().size(); i++) {
            ReportColumn column = report.columns().get(i);
            int index = i;
            columns.add(new Column<>(column.title(), column.type(), row -> row.get(index)));
        }
        ResultsTable<List<Object>> table = new ResultsTable<>(columns);
        resultArea.setViewportView(table);
        table.setRows(rows);
    }

    private void showSql() {
        Report report = list.getSelectedValue();
        JTextArea text = new JTextArea(ReportDao.sqlOf(report), 24, 90);
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text),
                report.id() + ": sql/queries/" + report.id() + ".sql (each :'name' is bound as a ? parameter)",
                JOptionPane.PLAIN_MESSAGE);
    }

    private void showMessage(String text, boolean good) {
        message.setForeground(good ? Messages.SUCCESS_COLOR : Messages.ERROR_COLOR);
        message.setText(text);
    }
}
