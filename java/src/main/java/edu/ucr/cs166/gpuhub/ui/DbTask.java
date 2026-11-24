package edu.ucr.cs166.gpuhub.ui;

import edu.ucr.cs166.gpuhub.db.Transactions;

import javax.swing.SwingWorker;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Runs database work on a background thread, so the window never freezes while PostgreSQL
 * is slow or down, and then calls back on the Swing thread with the result or the error.
 */
public final class DbTask {

    private DbTask() { }

    public static <T> void run(Transactions.Work<T> work, Consumer<T> onSuccess, Consumer<SQLException> onError) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws SQLException {
                return Transactions.run(work);
            }

            @Override
            protected void done() { // runs on the Swing thread
                T result;
                try {
                    result = get();
                } catch (ExecutionException e) {
                    onError.accept(e.getCause() instanceof SQLException sql
                            ? sql
                            : new SQLException("Program error: " + e.getCause(), e.getCause()));
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                onSuccess.accept(result);
            }
        }.execute();
    }
}
