package edu.ucr.cs166.gpuhub.ui;

import edu.ucr.cs166.gpuhub.db.SqlErrors;
import edu.ucr.cs166.gpuhub.db.Transactions;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The layout shared by every entity screen:
 *
 * <pre>
 *   filters ...                  [Search] [Clear]
 *   results table
 *   [Add] [Edit] [Delete]   message      n rows
 * </pre>
 *
 * A screen extends this class, adds its filters and implements search(). To enable Add,
 * Edit and Delete, it also implements Editor and calls setEditor(this).
 */
public abstract class EntityPanel<T> extends JPanel {

    private final String entityName;
    private final ResultsTable<T> table;
    private final JPanel filters = new JPanel(new WrapLayout(6, 4));
    private final List<JComponent> filterFields = new ArrayList<>();
    private final JButton searchButton = new JButton("Search");
    private final JButton clearButton = new JButton("Clear");
    private final JButton addButton = new JButton("Add");
    private final JButton editButton = new JButton("Edit");
    private final JButton deleteButton = new JButton("Delete");
    private final JPanel editButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final List<JButton> rowButtons = new ArrayList<>(List.of(editButton, deleteButton)); // need a selected row
    private final JLabel message = new JLabel(" ");
    private final JLabel rowCount = new JLabel(" ");

    private Editor<T> editor; // null: the screen is read-only
    private boolean busy = false;
    private int latestSearch = 0;

    /** @param entityName singular, for messages, such as "GPU type" */
    protected EntityPanel(String entityName, List<Column<T>> columns) {
        super(new BorderLayout(0, 8));
        this.entityName = entityName;
        this.table = new ResultsTable<>(columns);
        message.putClientProperty("html.disable", Boolean.TRUE); // it can contain user data
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JPanel searchButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        searchButtons.add(searchButton);
        searchButtons.add(clearButton);
        JPanel top = new JPanel(new BorderLayout());
        top.add(filters, BorderLayout.CENTER);
        top.add(searchButtons, BorderLayout.EAST);

        editButtons.add(addButton);
        editButtons.add(editButton);
        editButtons.add(deleteButton);
        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.add(editButtons, BorderLayout.WEST);
        bottom.add(message, BorderLayout.CENTER);
        bottom.add(rowCount, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        searchButton.addActionListener(e -> userSearch());
        clearButton.addActionListener(e -> clearFilters());
        addButton.addActionListener(e -> addNew());
        editButton.addActionListener(e -> editSelected());
        deleteButton.addActionListener(e -> deleteSelected());
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && editButton.isEnabled()) {
                    editSelected();
                }
            }
        });
        // Load fresh data each time the screen comes into view.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                withChoices(this::autoSearch, error -> showMessage(SqlErrors.message(error), false));
            }
        });
        // The filters may wrap differently at another width, so lay out again.
        filters.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                filters.revalidate();
            }
        });
        updateButtons();
    }

    /**
     * Reads the filters (on the Swing thread) and returns the query to run with them (on a
     * background thread). Throws InputException if a filter is invalid.
     */
    protected abstract Transactions.Work<List<T>> search();

    /**
     * For screens with dropdowns filled from the database: returns a query (run on a background
     * thread) whose result puts the choices into the dropdowns (run on the Swing thread). Called
     * when the screen is shown and before a form opens, so the choices are always up to date.
     */
    protected Transactions.Work<Runnable> loadChoices() {
        return null; // no such dropdowns
    }

    protected final void addFilter(String label, JComponent field) {
        JPanel pair = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); // stays together when wrapping
        pair.add(new JLabel(label));
        pair.add(field);
        filters.add(pair);
        filterFields.add(field);
        if (field instanceof JTextField text) {
            text.addActionListener(e -> userSearch()); // Enter searches
        }
    }

    protected final void setEditor(Editor<T> editor) {
        this.editor = editor;
        updateButtons();
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** The user asked for it, so problems are shown in a dialog. */
    private void userSearch() {
        showMessage(" ", true);
        runSearch(invalid -> Messages.invalidInput(this, invalid),
                error -> Messages.databaseError(this, error));
    }

    /** Happens by itself, so problems are shown in the message line instead of a dialog. */
    private void autoSearch() {
        runSearch(invalid -> showMessage(invalid.getMessage(), false),
                error -> showMessage(SqlErrors.message(error), false));
    }

    private void runSearch(Consumer<InputException> onInvalid, Consumer<SQLException> onError) {
        Transactions.Work<List<T>> work;
        try {
            work = search();
        } catch (InputException e) {
            onInvalid.accept(e);
            return;
        }
        int thisSearch = ++latestSearch;
        DbTask.run(work,
                rows -> {
                    if (thisSearch == latestSearch) { // ignore the answer to an older search
                        table.setRows(rows);
                        rowCount.setText(rows.size() == 1 ? "1 row" : rows.size() + " rows");
                    }
                },
                error -> {
                    if (thisSearch == latestSearch) {
                        onError.accept(error);
                    }
                });
    }

    private void clearFilters() {
        for (JComponent field : filterFields) {
            if (field instanceof JTextField text) {
                text.setText("");
            } else if (field instanceof JComboBox<?> box) {
                box.setSelectedIndex(0);
            }
        }
        userSearch();
    }

    // ------------------------------------------------------------------
    // Add, Edit, Delete
    // ------------------------------------------------------------------

    private void addNew() {
        openForm(() -> new FormDialog<>(this, "Add " + entityName, editor.form(null),
                editor::insert,
                (added, ok) -> finished(ok, entityName + " " + editor.nameOf(added) + " added.")));
    }

    private void editSelected() {
        T old = table.selected();
        openForm(() -> new FormDialog<>(this, "Edit " + entityName, editor.form(old),
                edited -> editor.update(old, edited),
                (edited, found) -> finished(found, entityName + " " + editor.nameOf(edited) + " updated.")));
    }

    /** Loads the dropdown choices first, because the form's constructor needs them. */
    private void openForm(Supplier<FormDialog<T>> dialog) {
        setBusy(true);
        withChoices(
                () -> {
                    setBusy(false);
                    try {
                        dialog.get().setVisible(true);
                    } catch (InputException e) { // the form refuses to edit this one
                        Messages.invalidInput(this, e);
                    }
                },
                error -> {
                    setBusy(false);
                    Messages.databaseError(this, error);
                });
    }

    private void withChoices(Runnable next, Consumer<SQLException> onError) {
        Transactions.Work<Runnable> work = loadChoices();
        if (work == null) {
            next.run();
            return;
        }
        DbTask.run(work,
                showChoices -> {
                    showChoices.run();
                    next.run();
                },
                onError);
    }

    private void deleteSelected() {
        T entity = table.selected();
        String name = entityName + " " + editor.nameOf(entity);
        if (!Messages.confirmDelete(this, name, editor.deleteWarning(entity))) {
            return;
        }
        runAction(editor.delete(entity), name + " deleted.");
    }

    /** Adds a button next to Delete that does something with the selected row. */
    protected final void addRowAction(String label, Consumer<T> action) {
        JButton button = new JButton(label);
        button.addActionListener(e -> action.accept(table.selected()));
        rowButtons.add(button);
        editButtons.add(button);
        updateButtons();
    }

    /** Runs work on the database in the background, then reports the result and reloads the table. */
    protected final void runAction(Transactions.Work<Boolean> work, String success) {
        setBusy(true);
        DbTask.run(work,
                found -> {
                    setBusy(false);
                    finished(found, success);
                },
                error -> {
                    setBusy(false);
                    String blockingTable = SqlErrors.blockingTable(error);
                    Messages.databaseError(this, error,
                            blockingTable == null ? null : editor.deleteHint(blockingTable));
                });
    }

    /** After a successful commit: reports it and reloads the table. */
    private void finished(boolean found, String success) {
        if (found) {
            showMessage("✓ " + success, true);
        } else {
            showMessage("That " + entityName + " no longer exists or was changed; the list has been refreshed.", false);
        }
        autoSearch();
    }

    private void showMessage(String text, boolean good) {
        message.setForeground(good ? Messages.SUCCESS_COLOR : Messages.ERROR_COLOR);
        message.setText(text);
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        updateButtons();
    }

    private void updateButtons() {
        boolean canEdit = editor != null && !busy;
        boolean rowSelected = table.getSelectedRow() >= 0;
        addButton.setEnabled(canEdit && editor.canAdd());
        addButton.setToolTipText(editor == null ? null : editor.addHint());
        for (JButton button : rowButtons) {
            button.setEnabled(canEdit && rowSelected);
        }
    }
}
