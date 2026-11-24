package edu.ucr.cs166.gpuhub.entity;

import java.math.BigDecimal;

/** A node as listed on screen, with data from its GPUs and staged checkpoints. gpuTypes may be null. */
public record NodeRow(
        ComputeNode node, int gpuCount, String gpuTypes, int stagedCount, BigDecimal stagedGb) { }
