package edu.ucr.cs166.gpuhub.ui;

import javax.swing.JTextField;
import java.math.BigDecimal;

/**
 * Reads and checks text fields before anything is sent to the database. Every method
 * throws InputException with a message for the user if the value is not acceptable.
 */
public final class Inputs {

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

    /**
     * A number greater than 0 that fits NUMERIC(digits, decimals). Too many decimals are
     * refused, because PostgreSQL would silently round them.
     */
    public static BigDecimal requiredPositive(String label, JTextField field, int digits, int decimals) {
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
        if (value.signum() <= 0) {
            throw new InputException(label + " must be more than 0.");
        }
        BigDecimal limit = BigDecimal.TEN.pow(digits - decimals); // NUMERIC(3,1) holds less than 100
        if (value.compareTo(limit) >= 0) {
            throw new InputException(label + " must be less than " + limit + ".");
        }
        if (value.stripTrailingZeros().scale() > decimals) {
            throw new InputException(label + " can have at most " + decimals
                    + (decimals == 1 ? " decimal place." : " decimal places."));
        }
        return value;
    }
}
