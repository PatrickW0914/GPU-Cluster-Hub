package edu.ucr.cs166.gpuhub.entity;

/**
 * An item of a dropdown: the key of the row it stands for and the text to show.
 *
 * @param <K> Integer for most tables, VersionKey for model versions
 */
public record Choice<K>(K key, String label) {

    @Override
    public String toString() { // JComboBox displays this
        return label;
    }
}
