package edu.ucr.cs166.gpuhub.ui;

/** The user typed something invalid. The message says which field and why. */
public final class InputException extends RuntimeException {

    public InputException(String message) {
        super(message);
    }
}
