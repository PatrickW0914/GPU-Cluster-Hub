package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.entity.GpuRow;
import edu.ucr.cs166.gpuhub.entity.GpuSlot;
import edu.ucr.cs166.gpuhub.ui.Messages;

import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Dimension;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A node's GPUs with a checkbox each. A GPU that is not free can still be ticked: the list may
 * be out of date, so the database decides (and refuses the whole booking).
 */
final class GpuPickTable extends JTable {

    private static final String[] TITLES = {"Book", "GPU", "Slot", "Type", "VRAM (GB)", "In this time window"};
    private static final Class<?>[] TYPES =
            {Boolean.class, Integer.class, Integer.class, String.class, Integer.class, String.class};

    private final Rows rows = new Rows();
    private final Set<Integer> pickedIds = new HashSet<>();
    private final Runnable onPick;

    /** @param onPick called when a checkbox was clicked */
    GpuPickTable(Runnable onPick) {
        this.onPick = onPick;
        setModel(rows);
        setRowHeight(24);
        setFillsViewportHeight(true);
        setRowSelectionAllowed(false);
        setIntercellSpacing(new Dimension(16, 1)); // keeps columns apart
        getTableHeader().setReorderingAllowed(false);
        getColumnModel().getColumn(5).setCellRenderer(new AvailabilityCell());
        getColumnModel().getColumn(3).setPreferredWidth(160);
        getColumnModel().getColumn(5).setPreferredWidth(240);
    }

    /** Shows the GPUs. Those that were ticked before stay ticked. */
    void setSlots(List<GpuSlot> slots) {
        rows.slots = slots;
        pickedIds.retainAll(slots.stream().map(s -> s.row().gpu().gpuId()).toList());
        rows.fireTableDataChanged();
    }

    /** The ticked GPUs, from top to bottom. */
    List<GpuSlot> picked() {
        return rows.slots.stream().filter(s -> pickedIds.contains(s.row().gpu().gpuId())).toList();
    }

    void clearPicks() {
        pickedIds.clear();
        rows.fireTableDataChanged();
    }

    private static String availability(GpuSlot slot) {
        GpuRow row = slot.row();
        if (!row.gpu().gpuStatus().equals("AVAILABLE")) {
            return row.gpu().gpuStatus();
        }
        if (!row.nodeStatus().equals("UP")) {
            return "Node is " + row.nodeStatus();
        }
        return slot.bookedBy() == null ? "Free" : "Booked (reservation " + slot.bookedBy() + ")";
    }

    private final class Rows extends AbstractTableModel {
        private List<GpuSlot> slots = List.of();

        @Override
        public int getRowCount() {
            return slots.size();
        }

        @Override
        public int getColumnCount() {
            return TITLES.length;
        }

        @Override
        public String getColumnName(int column) {
            return TITLES[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return TYPES[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            GpuSlot slot = slots.get(row);
            return switch (column) {
                case 0 -> pickedIds.contains(slot.row().gpu().gpuId());
                case 1 -> slot.row().gpu().gpuId();
                case 2 -> slot.row().gpu().slotIndex();
                case 3 -> slot.row().typeName();
                case 4 -> slot.row().vramGb();
                default -> availability(slot);
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            int gpuId = slots.get(row).row().gpu().gpuId();
            if ((Boolean) value) {
                pickedIds.add(gpuId);
            } else {
                pickedIds.remove(gpuId);
            }
            onPick.run();
        }
    }

    /** Green for free, red otherwise. */
    private static final class AvailabilityCell extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setForeground("Free".equals(value) ? Messages.SUCCESS_COLOR : Messages.ERROR_COLOR);
            return this;
        }
    }
}
