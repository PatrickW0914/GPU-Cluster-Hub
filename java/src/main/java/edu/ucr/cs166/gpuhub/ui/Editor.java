package edu.ucr.cs166.gpuhub.ui;

import edu.ucr.cs166.gpuhub.db.Transactions;

/**
 * What a screen must provide for Add, Edit and Delete. The methods return work that runs
 * later on a background thread; its result is false if the row no longer exists.
 */
public interface Editor<T> {

    /**
     * The form to fill in: empty for a new object (existing is null), otherwise with its values.
     * Throws InputException if this object cannot be edited.
     */
    EntityForm<T> form(T existing);

    Transactions.Work<Boolean> insert(T entity);

    Transactions.Work<Boolean> update(T old, T edited);

    Transactions.Work<Boolean> delete(T entity);

    /** A short name for messages, such as the hostname. */
    String nameOf(T entity);

    /** False if new objects are created somewhere else; addHint() then says where. */
    default boolean canAdd() {
        return true;
    }

    default String addHint() {
        return null;
    }

    /** What else deleting it removes (by cascade), to show in the confirmation; null if nothing. */
    default String deleteWarning(T entity) {
        return null;
    }

    /** What the user can do instead when rows of the given table block a delete; null if nothing. */
    default String deleteHint(String blockingTable) {
        return null;
    }
}
