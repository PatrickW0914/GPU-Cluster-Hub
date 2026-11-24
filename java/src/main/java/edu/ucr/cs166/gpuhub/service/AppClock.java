package edu.ucr.cs166.gpuhub.service;

import java.time.LocalDateTime;

/**
 * The app's "current time". The sample data is a snapshot, so by default the clock stands
 * still at the snapshot time; it can be switched to the real time. Time-dependent queries
 * get now() as a parameter instead of using the database's own clock.
 */
public final class AppClock {

    public static final LocalDateTime SNAPSHOT = LocalDateTime.of(2026, 11, 2, 9, 0);

    private boolean real = false;

    public boolean isReal() {
        return real;
    }

    public void setReal(boolean real) {
        this.real = real;
    }

    public LocalDateTime now() {
        return real ? LocalDateTime.now() : SNAPSHOT;
    }
}
