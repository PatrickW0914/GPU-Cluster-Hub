package edu.ucr.cs166.gpuhub.ui;

import edu.ucr.cs166.gpuhub.db.Transactions;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The add/edit dialog. Save checks the input, then runs the insert or update. If either
 * one fails, the dialog stays open so the user can correct the input.
 */
public final class FormDialog<T> extends JDialog {

    private final EntityForm<T> form;
    private final Function<T, Transactions.Work<Boolean>> save;
    private final BiConsumer<T, Boolean> onSaved;
    private final JButton saveButton = new JButton("Save");
    private final JButton cancelButton = new JButton("Cancel");

    /**
     * @param save    turns the object from the form into the database work that stores it
     * @param onSaved called after the commit with the object and the work's result (false: row no longer exists)
     */
    public FormDialog(Component parent, String title, EntityForm<T> form,
                      Function<T, Transactions.Work<Boolean>> save, BiConsumer<T, Boolean> onSaved) {
        super(SwingUtilities.getWindowAncestor(parent), title, ModalityType.APPLICATION_MODAL);
        this.form = form;
        this.save = save;
        this.onSaved = onSaved;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelButton);
        buttons.add(saveButton);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        saveButton.addActionListener(e -> save());
        cancelButton.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(saveButton); // Enter saves
        getRootPane().registerKeyboardAction(e -> cancelButton.doClick(), // Escape cancels
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setBusy(false);

        pack();
        setLocationRelativeTo(getOwner());
    }

    private void save() {
        final T entity;
        try {
            entity = form.read();
        } catch (InputException e) {
            Messages.invalidInput(this, e);
            return;
        }
        setBusy(true);
        DbTask.run(save.apply(entity),
                saved -> {
                    dispose();
                    onSaved.accept(entity, saved);
                },
                error -> {
                    setBusy(false);
                    Messages.databaseError(this, error);
                });
    }

    /** While saving, the dialog can be neither submitted again nor closed. */
    private void setBusy(boolean busy) {
        saveButton.setEnabled(!busy);
        cancelButton.setEnabled(!busy);
        setDefaultCloseOperation(busy ? DO_NOTHING_ON_CLOSE : DISPOSE_ON_CLOSE);
    }
}
