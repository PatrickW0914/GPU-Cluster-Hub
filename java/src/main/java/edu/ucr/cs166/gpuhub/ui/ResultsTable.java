package edu.ucr.cs166.gpuhub.ui;

import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** A read-only table that shows a list of objects. Click a column header to sort. */
public final class ResultsTable<T> extends JTable {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final int MAX_COLUMN_WIDTH = 280;
    private static final int ROWS_TO_MEASURE = 200; // enough to judge the widths, and fast

    private final Rows<T> rows;

    public ResultsTable(List<Column<T>> columns) {
        rows = new Rows<>(columns);
        setModel(rows);
        setAutoCreateRowSorter(true);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setRowHeight(24);
        setFillsViewportHeight(true);
        Cell cell = new Cell();
        setDefaultRenderer(Object.class, cell);
        setDefaultRenderer(Number.class, cell);
        setDefaultRenderer(Boolean.class, cell);
    }

    public void setRows(List<T> data) {
        rows.data = data;
        rows.fireTableDataChanged();
        fitColumns();
    }

    /** Makes each column as wide as its title and contents need, up to a maximum. */
    private void fitColumns() {
        for (int col = 0; col < getColumnCount(); col++) {
            TableColumn column = getColumnModel().getColumn(col);
            Component title = getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(this, column.getHeaderValue(), false, false, -1, col);
            int width = title.getPreferredSize().width + 24; // room for the sort arrow
            for (int row = 0; row < Math.min(getRowCount(), ROWS_TO_MEASURE); row++) {
                Component cell = prepareRenderer(getCellRenderer(row, col), row, col);
                width = Math.max(width, cell.getPreferredSize().width + 4);
            }
            column.setPreferredWidth(Math.min(width, MAX_COLUMN_WIDTH));
        }
    }

    /** Fill the available width, but scroll sideways instead of squeezing columns that do not fit. */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getPreferredSize().width < getParent().getWidth();
    }

    /** The selected object, or null. The view order can differ from the data order after sorting. */
    public T selected() {
        int viewRow = getSelectedRow();
        return viewRow < 0 ? null : rows.data.get(convertRowIndexToModel(viewRow));
    }

    private static final class Rows<T> extends AbstractTableModel {
        private final List<Column<T>> columns;
        private List<T> data = List.of();

        Rows(List<Column<T>> columns) {
            this.columns = columns;
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size();
        }

        @Override
        public String getColumnName(int column) {
            return columns.get(column).title();
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return columns.get(column).type();
        }

        @Override
        public Object getValueAt(int row, int column) {
            return columns.get(column).value().apply(data.get(row));
        }
    }

    /** Formats dates, numbers and booleans the same way on every screen. */
    private static final class Cell extends DefaultTableCellRenderer {
        Cell() {
            putClientProperty("html.disable", Boolean.TRUE); // show "<html>" in data as plain text
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6)); // keeps columns apart
            return this;
        }

        @Override
        protected void setValue(Object value) {
            setHorizontalAlignment(value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
            if (value == null) {
                setText("");
            } else if (value instanceof Boolean b) {
                setText(b ? "Yes" : "No");
            } else if (value instanceof LocalDateTime t) {
                setText(t.format(DATE_TIME));
            } else if (value instanceof BigDecimal d) {
                setText(d.toPlainString());
            } else {
                setText(value.toString());
            }
        }
    }
}
