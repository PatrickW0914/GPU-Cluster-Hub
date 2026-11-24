package edu.ucr.cs166.gpuhub.service;

import edu.ucr.cs166.gpuhub.entity.Report;
import edu.ucr.cs166.gpuhub.entity.ReportColumn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Descriptions of the queries Q1-Q8. Their SQL is in sql/queries. */
public final class Reports {

    private Reports() { }

    public static final List<Report> ALL = List.of(
            new Report("Q1", "Where can I run a model now?",
                    "Which running nodes host this model version with a usable environment, and how many GPUs are needed and free?",
                    "multi-table join (8 tables) with grouping", true, false, List.of(
                            text("Node"), text("GPU type"), integer("VRAM per GPU (GB)"), integer("GPUs needed"),
                            count("GPUs free now"), text("Usable environments"), text("Checkpoint path"))),
            new Report("Q2", "Quota usage this week",
                    "How much of its weekly quota has each group booked this week?",
                    "aggregate (SUM, COUNT) over an outer join", false, false, List.of(
                            text("Group"), number("Quota (GPU-hours)"), number("Booked (GPU-hours)"),
                            number("Used (%)"), count("Reservations"), count("Active users"))),
            new Report("Q3", "Reclaimable storage",
                    "Which staged checkpoints were not loaded on their node in the last 30 days?",
                    "correlated subquery (NOT EXISTS)", false, false, List.of(
                            text("Node"), count("Idle checkpoints"), number("Reclaimable (GB)"),
                            text("Checkpoints"))),
            new Report("Q4", "Used every GPU type",
                    "Which researchers used every bookable GPU type in the last 28 days?",
                    "relational division (NOT EXISTS ... EXCEPT)", false, false, List.of(
                            text("NetID"), text("Name"), text("Role"))),
            new Report("Q5", "Top GPU consumers",
                    "Who are the top 3 GPU consumers of each group in the last 28 days?",
                    "ranking (window function RANK)", false, false, List.of(
                            text("Group"), count("Rank"), text("NetID"), number("GPU-hours used"),
                            number("Average GPU utilization (%)"), number("Failed jobs (%)"))),
            new Report("Q6", "Booked but idle",
                    "Who wastes the most booked GPU time (bookings under 30% used, at least 20 idle GPU-hours, top 10, last 28 days)?",
                    "report (CTE, GROUP BY, HAVING)", false, false, List.of(
                            text("NetID"), text("Name"), count("Low-use bookings"), number("Booked (GPU-hours)"),
                            number("Used (GPU-hours)"), number("Idle (GPU-hours)"))),
            new Report("Q7", "Weekly model loads",
                    "How did model loads develop per task over the last 6 weeks?",
                    "trend report (conditional aggregates, COUNT ... FILTER)", false, false, List.of(
                            text("Week of"), count("LLM"), count("Diffusion"), count("Vision"), count("Detection"),
                            count("Multimodal"), count("Audio"), count("Jobs"))),
            new Report("Q8", "Earliest slot for a model",
                    "What is the earliest slot in the next 7 days to run this model version for this long?",
                    "time-slot search (CTEs, generate_series, range overlap)", true, true, List.of(
                            text("Node"), time("Slot start"), time("Slot end"), integer("GPUs needed"),
                            count("Free GPUs"))));

    // The Java type has to match the type the query returns: COUNT and RANK are BIGINT (Long).

    private static ReportColumn text(String title) {
        return new ReportColumn(title, String.class);
    }

    private static ReportColumn integer(String title) {
        return new ReportColumn(title, Integer.class);
    }

    private static ReportColumn count(String title) {
        return new ReportColumn(title, Long.class);
    }

    private static ReportColumn number(String title) {
        return new ReportColumn(title, BigDecimal.class);
    }

    private static ReportColumn time(String title) {
        return new ReportColumn(title, LocalDateTime.class);
    }
}
