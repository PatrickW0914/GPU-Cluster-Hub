package edu.ucr.cs166.gpuhub.entity;

import java.util.List;

/**
 * The values allowed by the CHECK constraints in sql/01_schema.sql, offered as dropdowns
 * so the user cannot type a wrong one. The database remains the final authority.
 */
public final class Allowed {

    private Allowed() { }

    public static final List<String> USER_ROLES = List.of("Faculty", "Postdoc", "PhD", "MS", "Undergrad", "Staff");
    public static final List<String> MEMBER_ROLES = List.of("Member", "Collaborator", "Manager");
    public static final List<String> PARTITIONS = List.of("gpu", "gpu-long", "gpu-short", "debug");
    public static final List<String> NODE_STATUSES = List.of("UP", "DRAIN", "DOWN");
    public static final List<String> VENDORS = List.of("NVIDIA", "AMD");
    public static final List<String> GPU_STATUSES = List.of("AVAILABLE", "MAINTENANCE", "RETIRED");
    public static final List<String> TASKS =
            List.of("LLM", "Vision", "Diffusion", "Audio", "Multimodal", "Detection", "Other");
    public static final List<String> FRAMEWORKS = List.of("PyTorch", "JAX", "TensorFlow", "ONNX");
    public static final List<String> PRECISIONS = List.of("fp32", "fp16", "bf16", "int8", "int4");
    public static final List<String> RES_STATUSES =
            List.of("PENDING", "CONFIRMED", "ACTIVE", "COMPLETED", "CANCELLED");
    public static final List<String> JOB_STATES =
            List.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED", "TIMEOUT");
}
