package edu.ucr.cs166.gpuhub.ui.screens;

import edu.ucr.cs166.gpuhub.dao.GroupDao;
import edu.ucr.cs166.gpuhub.dao.UserDao;
import edu.ucr.cs166.gpuhub.db.Transactions;
import edu.ucr.cs166.gpuhub.entity.Allowed;
import edu.ucr.cs166.gpuhub.entity.Choice;
import edu.ucr.cs166.gpuhub.entity.Membership;
import edu.ucr.cs166.gpuhub.entity.MembershipRow;
import edu.ucr.cs166.gpuhub.ui.ChoiceBox;
import edu.ucr.cs166.gpuhub.ui.Column;
import edu.ucr.cs166.gpuhub.ui.Editor;
import edu.ucr.cs166.gpuhub.ui.EntityForm;
import edu.ucr.cs166.gpuhub.ui.EntityPanel;
import edu.ucr.cs166.gpuhub.ui.FilterBox;
import edu.ucr.cs166.gpuhub.ui.Inputs;

import javax.swing.JComboBox;
import javax.swing.JTextField;
import java.time.LocalDate;
import java.util.List;

/** Which users belong to which groups. */
public final class MembershipsPanel extends EntityPanel<MembershipRow> implements Editor<MembershipRow> {

    private static final List<Column<MembershipRow>> COLUMNS = List.of(
            new Column<>("Group", String.class, MembershipRow::groupName),
            new Column<>("NetID", String.class, MembershipRow::netId),
            new Column<>("Name", String.class, MembershipRow::fullName),
            new Column<>("Member role", String.class, r -> r.membership().memberRole()),
            new Column<>("PI", Boolean.class, MembershipRow::isPi),
            new Column<>("Joined", LocalDate.class, r -> r.membership().joinedOn()));

    private final JTextField user = new JTextField(14);
    private final ChoiceBox<Integer> group = new ChoiceBox<>("Any");
    private final FilterBox role = new FilterBox(Allowed.MEMBER_ROLES);

    private List<Choice<Integer>> userChoices = List.of(); // for the form's dropdowns
    private List<Choice<Integer>> groupChoices = List.of();

    public MembershipsPanel() {
        super("Membership", COLUMNS);
        addFilter("User (NetID or name)", user);
        addFilter("Group", group);
        addFilter("Member role", role);
        setEditor(this);
    }

    @Override
    protected Transactions.Work<Runnable> loadChoices() {
        return c -> {
            List<Choice<Integer>> users = UsersPanel.choices(new UserDao(c), null, null);
            List<Choice<Integer>> groups = GroupsPanel.choices(new GroupDao(c), null);
            return () -> {
                userChoices = users;
                groupChoices = groups;
                group.setChoices(groups);
            };
        };
    }

    @Override
    protected Transactions.Work<List<MembershipRow>> search() {
        String userText = user.getText();
        Integer groupId = group.value();
        String roleValue = role.value();
        return c -> new GroupDao(c).searchMembers(userText, groupId, roleValue);
    }

    @Override
    public EntityForm<MembershipRow> form(MembershipRow existing) {
        return new Form(existing, userChoices, groupChoices);
    }

    @Override
    public Transactions.Work<Boolean> insert(MembershipRow row) {
        return c -> {
            new GroupDao(c).addMember(row.membership());
            return true;
        };
    }

    @Override
    public Transactions.Work<Boolean> update(MembershipRow old, MembershipRow edited) {
        return c -> new GroupDao(c).updateMember(edited.membership());
    }

    @Override
    public Transactions.Work<Boolean> delete(MembershipRow row) {
        return c -> new GroupDao(c).removeMember(row.membership().userId(), row.membership().groupId());
    }

    @Override
    public String nameOf(MembershipRow row) {
        return row.netId() + " in " + row.groupName();
    }

    @Override
    public String deleteHint(String blockingTable) {
        return switch (blockingTable) {
            case "reservation" -> "This member has booked GPUs for the group, so the membership is kept as "
                    + "history. If the person has left, deactivate the user instead: Users, Edit, untick Active.";
            case "research_group" -> "This member is the group's PI. "
                    + "Choose another PI first: Research groups, Edit.";
            default -> null;
        };
    }

    /** The user and group are the key, so they can only be chosen when adding. */
    private static final class Form extends EntityForm<MembershipRow> {

        private final MembershipRow existing; // null for a new membership
        private final ChoiceBox<Integer> user = new ChoiceBox<>("Select a user");
        private final ChoiceBox<Integer> group = new ChoiceBox<>("Select a group");
        private final JComboBox<String> role = new JComboBox<>(Allowed.MEMBER_ROLES.toArray(new String[0]));

        Form(MembershipRow existing, List<Choice<Integer>> userChoices, List<Choice<Integer>> groupChoices) {
            this.existing = existing;
            addField("User", user);
            addField("Group", group);
            addField("Member role", role);
            user.setChoices(userChoices);
            group.setChoices(groupChoices);
            if (existing != null) {
                user.select(existing.membership().userId());
                group.select(existing.membership().groupId());
                role.setSelectedItem(existing.membership().memberRole());
                user.setEnabled(false);
                group.setEnabled(false);
            }
        }

        @Override
        protected MembershipRow read() {
            String roleValue = (String) role.getSelectedItem();
            if (existing != null) {
                Membership m = existing.membership();
                return new MembershipRow(new Membership(m.userId(), m.groupId(), roleValue, m.joinedOn()),
                        existing.netId(), existing.fullName(), existing.groupName(), existing.isPi());
            }
            int userId = Inputs.requiredChoice("User", user);
            int groupId = Inputs.requiredChoice("Group", group);
            // The names are only used in the success message; the list is reloaded afterwards.
            return new MembershipRow(new Membership(userId, groupId, roleValue, null),
                    user.getSelectedItem().toString(), null, group.getSelectedItem().toString(), false);
        }
    }
}
