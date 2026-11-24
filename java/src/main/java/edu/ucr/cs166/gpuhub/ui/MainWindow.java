package edu.ucr.cs166.gpuhub.ui;

import edu.ucr.cs166.gpuhub.service.AppClock;
import edu.ucr.cs166.gpuhub.ui.screens.EnvsPanel;
import edu.ucr.cs166.gpuhub.ui.screens.GpuTypesPanel;
import edu.ucr.cs166.gpuhub.ui.screens.GpusPanel;
import edu.ucr.cs166.gpuhub.ui.screens.GroupsPanel;
import edu.ucr.cs166.gpuhub.ui.screens.JobsPanel;
import edu.ucr.cs166.gpuhub.ui.screens.MembershipsPanel;
import edu.ucr.cs166.gpuhub.ui.screens.ModelsPanel;
import edu.ucr.cs166.gpuhub.ui.screens.NodesPanel;
import edu.ucr.cs166.gpuhub.ui.screens.ReservationsPanel;
import edu.ucr.cs166.gpuhub.ui.screens.UsersPanel;
import edu.ucr.cs166.gpuhub.ui.screens.VersionsPanel;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

/** The main window: navigation on the left, the chosen section in the middle, status bar below. */
public final class MainWindow extends JFrame {

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards); // shows one section at a time
    private final DefaultListModel<String> sectionNames = new DefaultListModel<>();

    public MainWindow(AppClock clock) {
        super("GPU Cluster Hub");

        addSection("Catalog", tabs()
                .with("Models", new ModelsPanel())
                .with("Model versions", new VersionsPanel())
                .with("Environments", new EnvsPanel()));
        addSection("GPU Booking", new PlaceholderPanel("GPU Booking is not built yet."));
        addSection("Reservations & Jobs", tabs()
                .with("Reservations", new ReservationsPanel())
                .with("Jobs", new JobsPanel()));
        addSection("Groups & Users", tabs()
                .with("Users", new UsersPanel())
                .with("Research groups", new GroupsPanel())
                .with("Memberships", new MembershipsPanel()));
        addSection("Inventory (admin)", tabs()
                .with("Nodes", new NodesPanel())
                .with("GPU types", new GpuTypesPanel())
                .with("GPUs", new GpusPanel()));
        addSection("Queries & Reports", new PlaceholderPanel("Queries & Reports is not built yet."));

        add(navigation(), BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        add(new StatusBar(clock), BorderLayout.SOUTH);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 500));
        setSize(1200, 720);
        setLocationRelativeTo(null); // center on the screen
    }

    private void addSection(String name, JComponent section) {
        sectionNames.addElement(name);
        content.add(section, name);
    }

    private JComponent navigation() {
        JList<String> list = new JList<>(sectionNames);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(40);
        list.setFixedCellWidth(180);
        list.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(l, value, index, selected, focused);
                label.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
                return label;
            }
        });
        list.addListSelectionListener(e -> {
            if (list.getSelectedValue() != null) {
                cards.show(content, list.getSelectedValue());
            }
        });
        list.setSelectedIndex(0);
        return list;
    }

    private static Tabs tabs() {
        return new Tabs();
    }

    /** A tabbed pane whose tabs can be added in one expression. */
    private static final class Tabs extends JTabbedPane {
        Tabs with(String title, JComponent panel) {
            addTab(title, panel);
            return this;
        }
    }
}
