package com.dbthelper.core

import com.dbthelper.core.model.*
import java.util.LinkedList

class LineageGraphBuilder(private val index: ManifestIndex) {

    fun build(
        currentNodeId: String,
        upstreamDepth: Int = 5,
        downstreamDepth: Int = 5,
        showTestNodes: Boolean = false,
        showExposures: Boolean = true,
        expandedBoundaryNodes: Set<String> = emptySet()
    ): LineageGraph {
        val visitedNodes = mutableMapOf<String, Int>() // id -> depth
        val edges = mutableListOf<LineageEdge>()

        // BFS upstream (negative depth)
        val upstreamResult = bfs(
            startId = currentNodeId,
            maxDepth = upstreamDepth,
            direction = Direction.UPSTREAM,
            visitedNodes = visitedNodes,
            edges = edges
        )

        // BFS downstream (positive depth)
        val downstreamResult = bfs(
            startId = currentNodeId,
            maxDepth = downstreamDepth,
            direction = Direction.DOWNSTREAM,
            visitedNodes = visitedNodes,
            edges = edges
        )

        // Expand specific boundary nodes with extra depth
        val expandStep = 5
        for (boundaryId in expandedBoundaryNodes) {
            if (boundaryId in upstreamResult.boundaryHiddenCounts) {
                bfs(
                    startId = boundaryId,
                    maxDepth = expandStep,
                    direction = Direction.UPSTREAM,
                    visitedNodes = visitedNodes,
                    edges = edges
                )
            }
            if (boundaryId in downstreamResult.boundaryHiddenCounts) {
                bfs(
                    startId = boundaryId,
                    maxDepth = expandStep,
                    direction = Direction.DOWNSTREAM,
                    visitedNodes = visitedNodes,
                    edges = edges
                )
            }
        }

        // Ensure current node is included
        visitedNodes[currentNodeId] = 0

        var lineageNodes = visitedNodes.mapNotNull { (id, depth) ->
            toLineageNode(id, depth, id == currentNodeId)
        }.toMutableList()

        // Filter by settings
        if (!showTestNodes) {
            lineageNodes = lineageNodes.filter { it.resourceType != "test" }.toMutableList()
        }
        if (!showExposures) {
            lineageNodes = lineageNodes.filter { it.resourceType != "exposure" }.toMutableList()
        }

        // Recalculate stub counts — exclude nodes already visible after expands
        val visibleNodeIds = visitedNodes.keys
        val stubEdges = mutableListOf<LineageEdge>()
        for ((boundaryId, _) in upstreamResult.boundaryHiddenCounts) {
            if (boundaryId in expandedBoundaryNodes) continue
            // Count upstream neighbors of this boundary that are NOT in the visible graph
            val hiddenNeighbors = getNeighbors(boundaryId, Direction.UPSTREAM).count { it !in visibleNodeIds }
            if (hiddenNeighbors == 0) continue
            val stubId = "__stub_upstream_$boundaryId"
            lineageNodes.add(LineageNode(
                id = stubId,
                name = "+ $hiddenNeighbors more",
                resourceType = "stub",
                schema = null, database = null, materialization = null,
                filePath = null, description = null, columns = emptyList(),
                depth = -(upstreamDepth + 1),
                isCurrent = false,
                stubDirection = "upstream",
                boundaryNodeId = boundaryId
            ))
            stubEdges.add(LineageEdge(fromNodeId = stubId, toNodeId = boundaryId))
        }
        for ((boundaryId, _) in downstreamResult.boundaryHiddenCounts) {
            if (boundaryId in expandedBoundaryNodes) continue
            val hiddenNeighbors = getNeighbors(boundaryId, Direction.DOWNSTREAM).count { it !in visibleNodeIds }
            if (hiddenNeighbors == 0) continue
            val stubId = "__stub_downstream_$boundaryId"
            lineageNodes.add(LineageNode(
                id = stubId,
                name = "+ $hiddenNeighbors more",
                resourceType = "stub",
                schema = null, database = null, materialization = null,
                filePath = null, description = null, columns = emptyList(),
                depth = downstreamDepth + 1,
                isCurrent = false,
                stubDirection = "downstream",
                boundaryNodeId = boundaryId
            ))
            stubEdges.add(LineageEdge(fromNodeId = boundaryId, toNodeId = stubId))
        }

        // Filter edges — only keep edges where both endpoints are in the graph
        val nodeIds = lineageNodes.map { it.id }.toSet()
        val validEdges = edges.distinct().filter { it.fromNodeId in nodeIds && it.toNodeId in nodeIds } + stubEdges

        return LineageGraph(
            currentNodeId = currentNodeId,
            nodes = lineageNodes,
            edges = validEdges,
            hiddenUpstreamCount = upstreamResult.hiddenCount,
            hiddenDownstreamCount = downstreamResult.hiddenCount
        )
    }

    data class BfsResult(val hiddenCount: Int, val boundaryHiddenCounts: Map<String, Int>)

    private fun bfs(
        startId: String,
        maxDepth: Int,
        direction: Direction,
        visitedNodes: MutableMap<String, Int>,
        edges: MutableList<LineageEdge>
    ): BfsResult {
        val queue = LinkedList<Pair<String, Int>>() // (nodeId, currentDepth)
        val visited = mutableSetOf(startId)
        var hiddenCount = 0
        val boundaryHiddenCounts = mutableMapOf<String, Int>() // boundaryNodeId -> count of hidden beyond it

        // Seed with immediate neighbors
        val neighbors = getNeighbors(startId, direction)
        for (neighborId in neighbors) {
            queue.add(neighborId to 1)
            addEdge(edges, startId, neighborId, direction)
        }

        while (queue.isNotEmpty()) {
            val (nodeId, depth) = queue.poll()

            if (nodeId in visited) continue
            visited.add(nodeId)

            if (depth > maxDepth) {
                hiddenCount++
                continue
            }

            val signedDepth = if (direction == Direction.UPSTREAM) -depth else depth
            // Keep the depth closest to current node
            val existing = visitedNodes[nodeId]
            if (existing == null || kotlin.math.abs(signedDepth) < kotlin.math.abs(existing)) {
                visitedNodes[nodeId] = signedDepth
            }

            val nextNeighbors = getNeighbors(nodeId, direction)
            // Track boundary nodes — at maxDepth with hidden neighbors beyond
            if (depth == maxDepth) {
                val hiddenNeighbors = nextNeighbors.count { it !in visited }
                if (hiddenNeighbors > 0) {
                    boundaryHiddenCounts[nodeId] = hiddenNeighbors
                }
            }
            for (nextId in nextNeighbors) {
                addEdge(edges, nodeId, nextId, direction)
                if (nextId !in visited) {
                    queue.add(nextId to depth + 1)
                }
            }
        }

        return BfsResult(hiddenCount, boundaryHiddenCounts)
    }

    private fun getNeighbors(nodeId: String, direction: Direction): List<String> {
        return when (direction) {
            Direction.UPSTREAM -> index.getUpstream(nodeId)
            Direction.DOWNSTREAM -> index.getDownstream(nodeId)
        }
    }

    private fun addEdge(
        edges: MutableList<LineageEdge>,
        fromId: String,
        toId: String,
        direction: Direction
    ) {
        when (direction) {
            Direction.UPSTREAM -> edges.add(LineageEdge(fromNodeId = toId, toNodeId = fromId))
            Direction.DOWNSTREAM -> edges.add(LineageEdge(fromNodeId = fromId, toNodeId = toId))
        }
    }

    private fun toLineageNode(id: String, depth: Int, isCurrent: Boolean): LineageNode? {
        // Try nodes first
        index.nodes[id]?.let { node ->
            return LineageNode(
                id = id,
                name = node.name,
                resourceType = node.resourceType,
                schema = node.schema,
                database = node.database,
                materialization = node.config["materialized"] as? String,
                filePath = node.originalFilePath,
                description = node.description.ifEmpty { null },
                columns = node.columns.values.map { col ->
                    ColumnNode(col.name, col.dataType, col.description.ifEmpty { null })
                },
                depth = depth,
                isCurrent = isCurrent
            )
        }

        // Try sources
        index.sources[id]?.let { source ->
            return LineageNode(
                id = id,
                name = "${source.sourceName}.${source.name}",
                resourceType = "source",
                schema = source.schema,
                database = source.database,
                materialization = null,
                filePath = source.originalFilePath,
                description = source.description.ifEmpty { null },
                columns = source.columns.values.map { col ->
                    ColumnNode(col.name, col.dataType, col.description.ifEmpty { null })
                },
                depth = depth,
                isCurrent = isCurrent
            )
        }

        // Try exposures
        index.exposures[id]?.let { exposure ->
            return LineageNode(
                id = id,
                name = exposure.name,
                resourceType = "exposure",
                schema = null,
                database = null,
                materialization = null,
                filePath = exposure.originalFilePath,
                description = exposure.description.ifEmpty { null },
                columns = emptyList(),
                depth = depth,
                isCurrent = isCurrent
            )
        }

        return null
    }

    private enum class Direction {
        UPSTREAM, DOWNSTREAM
    }
}
