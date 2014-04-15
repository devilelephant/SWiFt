package com.clario.swift;

import java.util.Set;

/**
 * Simple Directed Graph Vertex
 *
 * @author George Coller
 */
interface Vertex {
    String getStepId();

    Set<? extends Vertex> getChildren();
}