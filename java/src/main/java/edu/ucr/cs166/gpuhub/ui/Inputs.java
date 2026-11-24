package edu.ucr.cs166.gpuhub.ui;

import javax.swing.JTextField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Reads and checks text fields before anything is sent to the database. Every method
 * throws InputException with a message for the user if the value is not acceptable.
 */
public final class Inputs {

    /**
     * The format of every date-time field, for example 2026-11-02 09:00. STRICT (with uuuu for
     * the year) refuses dates that do not exist, such as 2026-02-30.
     */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private Inputs() { }

    /** Text that must be filled in and fit the column, e.g. VARCHAR(40). */
    public static String requiredText(String label, JTextField field, int maxLength) {
        String text = optionalText(label, field, maxLength);
        if (text == null) {
            throw new InputException(label + " is required.");
        }
        return text;
    }

    /** Text that may be left blank; returns null if it is. */
    public static String optionalText(String label, JTextField field, int maxLength) {
        String text = field.getText().strip();
        if (text.length() > maxLength) {
            throw new InputException(label + " can have at most " + maxLength + " characters.");
        }
        return text.isEmpty() ? null : text;
    }

    /** Required text that must match a pattern; rule is the message if it does not. */
    public static String requiredMatch(String label, JTextField field, int maxLength, String regex, String rule) {
        String text = requiredText(label, field, maxLength);
        if (!text.matches(regex)) {
            throw new InputException(rule);
        }
        return text;
    }

    /** A dropdown in which something must be chosen; returns the chosen key. */
    public static <K> K requiredChoice(String label, ChoiceBox<K> box) {
        if (box.value() == null) {
            throw new InputException(label + " is required.");
        }
        return box.value();
    }

    public static int requiredInt(String label, JTextField field, int min, int max) {
        Integer value = optionalInt(label, field, min, max);
        if (value == null) {
            throw new InputException(label + " is required.");
        }
        return value;
    }

    /** A whole number that may be left blank; returns null if it is. */
    public static Integer optionalInt(String label, JTextField field, int min, int max) {
        String text = field.getText().strip();
        if (text.isEmpty()) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new InputException(label + " must be a whole number.");
        }
        if (value < min || value > max) {
            throw new InputException(label + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    /** A number greater than 0 that fits NUMERIC(digits, decimals). */
    public static BigDecimal requiredPositive(String label, JTextField field, int digits, int decimals) {
        BigDecimal value = requiredNumber(label, field, decimals);
        if (value.signum() <= 0) {
            throw new InputException(label + " must be more than 0.");
        }
        BigDecimal limit = BigDecimal.TEN.pow(digits - decimals); // NUMERIC(3,1) holds less than 100
        if (value.compareTo(limit) >= 0) {
            throw new InputException(label + " must be less than " + limit + ".");
        }
        return value;
    }

    /** A number from min to max, or null if left blank. */
    public static BigDecimal optionalInRange(String label, JTextField field,
                                             BigDecimal min, BigDecimal max, int decimals) {
        return field.getText().isBlank() ? null : requiredInRange(label, field, min, max, decimals);
    }

    /** A number from min to max, both allowed. */
    public static BigDecimal requiredInRange(String label, JTextField field,
                                             BigDecimal min, BigDecimal max, int decimals) {
        BigDecimal value = requiredNumber(label, field, decimals);
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new InputException(label + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    /** Too many decimals are refused, because PostgreSQL would silently round them. */
    private static BigDecimal requiredNumber(String label, JTextField field, int decimals) {
        String text = field.getText().strip();
        if (text.isEmpty()) {
            throw new InputException(label + " is required.");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new InputException(label + " must be a number.");
        }
        if (value.stripTrailingZeros().scale() > decimals) {
            throw new InputException(label + " can have at most " + decimals
                    + (decimals == 1 ? " decimal place." : " decimal places."));
        }
        return value;
    }

    public static LocalDate requiredDate(String label, JTextField field) {
        String text = field.getText().strip();
        if (text.isEmpty()) {
            throw new InputException(label + " is required.");
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new InputException(label + " must be a date like 2026-11-02.");
        }
    }

    /** A date, or null if left blank. */
    public static LocalDate optionalDate(String label, JTextField field) {
        return field.getText().isBlank() ? null : requiredDate(label, field);
    }

    public static LocalDateTime requiredDateTime(String label, JTextField field) {
        String text = field.getText().strip();
        if (text.isEmpty()) {
            throw new InputException(label + " is required.");
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new InputException(label + " must be a date and time like 2026-11-02 09:00.");
        }
    }

    /** A date and time, or null if left blank. */
    public static LocalDateTime optionalDateTime(String label, JTextField field) {
        return field.getText().isBlank() ? null : requiredDateTime(label, field);
    }

    /** The text to put into a date-time field; blank for null. */
    public static String dateTimeText(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME);
    }
}
