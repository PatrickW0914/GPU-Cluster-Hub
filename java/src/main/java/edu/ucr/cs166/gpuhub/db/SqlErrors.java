package edu.ucr.cs166.gpuhub.db;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that turns a database error into text for the user.
 *
 * <ul>
 *   <li>{@link #message} - short, friendly message, chosen by SQLSTATE and, for
 *       constraint violations, by the constraint's name.</li>
 *   <li>{@link #detail} - the database's own text. For our triggers (GH001-GH008)
 *       this is already readable.</li>
 * </ul>
 *
 * Deferred rules (GH004 and the group-PI foreign key) are thrown by commit(), so
 * commit() must be inside the same try block as the statements before it.
 */
public final class SqlErrors {

    private SqlErrors() { }

    /** The trigger text for GH001 starts with "GPU 17 is already booked by ...". */
    private static final Pattern GPU_NUMBER = Pattern.compile("^GPU (\\d+) ");

    /** Returns the friendly message for an error. */
    public static String message(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("08")) {
            return "Can't reach the database — check config/db.properties and that PostgreSQL is running.";
        }
        return switch (state) {
            case "23505" -> uniqueMessage(e);
            case "23503" -> foreignKeyMessage(e);
            case "23502" -> "Field " + columnLabel(e) + " is required.";
            case "23514" -> checkMessage(e);
            case "GH001" -> doubleBookedMessage(e);
            case "GH002" -> "That GPU is in maintenance/retired or its node isn't UP.";
            case "GH003" -> "This booking would exceed your group's weekly GPU-hour quota.";
            case "GH004" -> "Select at least one GPU.";
            case "GH005" -> "Job times must fall within the reservation.";
            case "GH006" -> "That model version isn't verified for this environment.";
            case "GH007" -> "Not enough NVMe space on that node.";
            case "GH008" -> "A group's PI must be a Faculty user.";
            default -> "Unexpected database error (code " + (state.isEmpty() ? "unknown" : state) + ").";
        };
    }

    /** Returns the database's own explanation, to show under the friendly message. */
    public static String detail(SQLException e) {
        ServerErrorMessage server = serverError(e);
        if (server == null || server.getMessage() == null) {
            return e.getMessage() == null ? "" : e.getMessage(); // raised by the driver, not the server
        }
        // Constraint errors carry a second line such as "Key (net_id)=(abc) already exists."
        return server.getDetail() == null
                ? server.getMessage()
                : server.getMessage() + "\n" + server.getDetail();
    }

    // ------------------------------------------------------------------
    // One helper per kind of violation
    // ------------------------------------------------------------------

    private static String uniqueMessage(SQLException e) {
        return UNIQUE_RULES.getOrDefault(constraintName(e), "That record already exists.");
    }

    /**
     * 23503 covers two different mistakes. PostgreSQL names the same constraint and
     * table for both, so only the message text tells them apart:
     *   parent side: update or delete on table "gpu" violates foreign key constraint ...
     *   child side:  insert or update on table "gpu" violates foreign key constraint ...
     */
    private static String foreignKeyMessage(SQLException e) {
        if (detail(e).startsWith("update or delete on table")) {
            ServerErrorMessage server = serverError(e);
            String table = server == null ? null : server.getTable(); // the referencing table
            return "Can't delete: this record is still referenced by "
                    + (table == null ? "other records" : TABLE_LABELS.getOrDefault(table, table)) + ".";
        }
        return switch (constraintName(e)) {
            case "fk_res_membership" ->
                    "Referenced record doesn't exist: that user is not a member of that group.";
            case "fk_group_pi_membership" ->
                    "Referenced record doesn't exist: the PI must be a member of the group.";
            default -> "Referenced record doesn't exist.";
        };
    }

    private static String checkMessage(SQLException e) {
        String name = constraintName(e);
        return CHECK_RULES.getOrDefault(name, "A value breaks the rule " + name + ".");
    }

    private static String doubleBookedMessage(SQLException e) {
        Matcher m = GPU_NUMBER.matcher(detail(e));
        return (m.find() ? "GPU " + m.group(1) : "That GPU") + " is already booked during that time.";
    }

    // ------------------------------------------------------------------
    // Reading the error PostgreSQL sent
    // ------------------------------------------------------------------

    /**
     * Finds the server's structured error (constraint, table, column), or null if the
     * server sent none. Iterating over a SQLException also visits its causes and "next"
     * exceptions, which is where a batch update hides the real error.
     */
    private static ServerErrorMessage serverError(SQLException e) {
        for (Throwable t : e) {
            if (t instanceof PSQLException p && p.getServerErrorMessage() != null) {
                return p.getServerErrorMessage();
            }
        }
        return null;
    }

    private static String constraintName(SQLException e) {
        ServerErrorMessage server = serverError(e);
        return server == null || server.getConstraint() == null ? "" : server.getConstraint();
    }

    /** "full_name" becomes "full name". */
    private static String columnLabel(SQLException e) {
        ServerErrorMessage server = serverError(e);
        return server == null || server.getColumn() == null ? "" : server.getColumn().replace('_', ' ');
    }

    // ------------------------------------------------------------------
    // Constraint name -> readable rule. Names come from sql/01_schema.sql.
    // ------------------------------------------------------------------

    /** UNIQUE and PRIMARY KEY constraints a user can violate (generated ids are left out). */
    private static final Map<String, String> UNIQUE_RULES = Map.ofEntries(
            Map.entry("uq_user_netid",      "A user with that NetID already exists."),
            Map.entry("uq_user_email",      "A user with that email already exists."),
            Map.entry("uq_group_name",      "A research group with that name already exists."),
            Map.entry("uq_node_hostname",   "A node with that hostname already exists."),
            Map.entry("uq_gputype_name",    "A GPU type with that name already exists."),
            Map.entry("uq_gpu_serial",      "A GPU with that serial number already exists."),
            Map.entry("uq_gpu_node_slot",   "That slot on the node already holds a GPU."),
            Map.entry("uq_model_name",      "A model with that name already exists."),
            Map.entry("uq_env_name",        "An environment with that name already exists."),
            Map.entry("uq_env_image",       "An environment with that container image already exists."),
            Map.entry("uq_hosts_path",      "Another checkpoint already uses that path on this node."),
            Map.entry("pk_membership",      "That user is already a member of this group."),
            Map.entry("pk_model_version",   "That model already has a version with this tag."),
            Map.entry("pk_hosts",           "That model version is already staged on this node."),
            Map.entry("pk_env_supports",    "That model version is already verified for this environment."),
            Map.entry("pk_reservation_gpu", "That GPU is already part of this reservation."),
            Map.entry("pk_job_loads",       "That job already loads this model version."));

    private static final Map<String, String> CHECK_RULES = Map.ofEntries(
            // users, groups, memberships
            Map.entry("ck_user_netid",      "NetID must be 3-20 lowercase letters or digits, starting with a letter."),
            Map.entry("ck_user_email",      "Enter a valid email address, such as name@example.edu."),
            Map.entry("ck_user_role",       "Role must be Faculty, Postdoc, PhD, MS, Undergrad or Staff."),
            Map.entry("ck_group_quota",     "Weekly GPU-hour quota must be more than 0 and at most 10000."),
            Map.entry("ck_mem_role",        "Member role must be Member, Collaborator or Manager."),
            // nodes, GPU types, GPUs
            Map.entry("ck_node_partition",  "Partition must be gpu, gpu-long, gpu-short or debug."),
            Map.entry("ck_node_cpu",        "CPU cores must be between 1 and 512."),
            Map.entry("ck_node_ram",        "RAM must be between 1 and 8192 GB."),
            Map.entry("ck_node_nvme",       "Local NVMe can't be negative."),
            Map.entry("ck_node_cuda",       "Max CUDA version must be between 9.0 and 20.0."),
            Map.entry("ck_node_status",     "Node status must be UP, DRAIN or DOWN."),
            Map.entry("ck_gputype_vendor",  "Vendor must be NVIDIA or AMD."),
            Map.entry("ck_gputype_vram",    "VRAM must be more than 0 GB."),
            Map.entry("ck_gputype_cc",      "Compute capability must be more than 0."),
            Map.entry("ck_gputype_tflops",  "FP16 TFLOPS must be more than 0."),
            Map.entry("ck_gpu_slot",        "Slot must be between 0 and 15."),
            Map.entry("ck_gpu_status",      "GPU status must be AVAILABLE, MAINTENANCE or RETIRED."),
            // models, versions, environments, staged copies
            Map.entry("ck_model_task",      "Task must be LLM, Vision, Diffusion, Audio, Multimodal, Detection or Other."),
            Map.entry("ck_model_framework", "Framework must be PyTorch, JAX, TensorFlow or ONNX."),
            Map.entry("ck_model_url",       "Source URL must start with http:// or https://."),
            Map.entry("ck_mv_params",       "Parameter count must be more than 0."),
            Map.entry("ck_mv_precision",    "Precision must be fp32, fp16, bf16, int8 or int4."),
            Map.entry("ck_mv_vram",         "Minimum VRAM must be more than 0 GB."),
            Map.entry("ck_mv_size",         "Checkpoint size must be more than 0 GB."),
            Map.entry("ck_env_python",      "Python version must look like 3.11."),
            Map.entry("ck_env_cuda",        "CUDA version must be between 9.0 and 20.0."),
            Map.entry("ck_env_framework",   "Framework must be PyTorch, JAX, TensorFlow or ONNX."),
            Map.entry("ck_hosts_path",      "Local path must start with /."),
            // reservations
            Map.entry("ck_res_window",      "A reservation must end after it starts."),
            Map.entry("ck_res_maxlen",      "Reservations can't exceed 72 hours."),
            Map.entry("ck_res_status",      "Status must be PENDING, CONFIRMED, ACTIVE, COMPLETED or CANCELLED."),
            Map.entry("ck_res_created",     "A reservation can't start more than 1 hour before it is created."),
            // jobs
            Map.entry("ck_job_state",       "Job state must be QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED or TIMEOUT."),
            Map.entry("ck_job_start",       "A job can't start before it is submitted."),
            Map.entry("ck_job_end",         "A job needs a start time before its end time."),
            Map.entry("ck_job_state_times", "The job's start and end times don't fit its state (for example, a COMPLETED job needs both)."),
            Map.entry("ck_job_exit",        "COMPLETED jobs need exit code 0; FAILED jobs need a non-zero exit code."),
            Map.entry("ck_job_vram",        "Peak VRAM can't be negative."),
            Map.entry("ck_job_util",        "GPU utilization must be between 0 and 100 %."),
            Map.entry("ck_jl_role",         "Usage must be inference, fine-tune, pretrain, evaluation, attack or distillation."));

    /** How to name a referencing table in "still referenced by ...". */
    private static final Map<String, String> TABLE_LABELS = Map.ofEntries(
            Map.entry("research_group",       "research groups"),
            Map.entry("membership",           "group memberships"),
            Map.entry("gpu",                  "GPUs"),
            Map.entry("ai_model",             "models"),
            Map.entry("model_version",        "model versions"),
            Map.entry("node_hosts_version",   "staged checkpoints"),
            Map.entry("env_supports_version", "environment compatibility records"),
            Map.entry("reservation",          "reservations"),
            Map.entry("reservation_gpu",      "reservations"),
            Map.entry("job",                  "jobs"),
            Map.entry("job_loads_version",    "jobs that load it"));
}
