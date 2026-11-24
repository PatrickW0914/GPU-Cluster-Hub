package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A slot suggested by the slot finder.
 *
 * @param optionNo 1 for the earliest slot on this node, 2 for the next one ...
 * @param gpuIds   the GPUs to book; all on this node and of the same type
 */
public record SlotCandidate(
        String hostname, int optionNo, LocalDateTime start, LocalDateTime end,
        String typeName, int vramGb, BigDecimal minVramGb, int gpusNeeded, int freeGpus,
        List<Integer> gpuIds, String envs) {

    public int totalVramGb() {
        return gpusNeeded * vramGb;
    }
}
