package com.dbthelper.core.model

data class LineageGraph(
    val currentNodeId: String,
    val nodes: List<LineageNode>,
    val edges: List<LineageEdge>,
    val hiddenUpstreamCount: Int = 0,
    val hiddenDownstreamCount: Int = 0,
    val edgeCurveStyle: String = "bezier",
    val layoutDirection: String = "LR"
)

data class LineageNode(
    val id: String,
    val name: String,
    val resourceType: String,
    val schema: String?,
    val database: String?,
    val materialization: String?,
    val filePath: String?,
    val description: String?,
    val columns: List<ColumnNode> = emptyList(),
    val depth: Int,
    val isCurrent: Boolean = false,
    val stubDirection: String? = null,
    val boundaryNodeId: String? = null
)

data class ColumnNode(
    val name: String,
    val dataType: String?,
    val description: String?
)

data class LineageEdge(
    val fromNodeId: String,
    val toNodeId: String
)

