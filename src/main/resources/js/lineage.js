// dbt Lineage Graph — Cytoscape.js + Dagre
(function () {
    'use strict';

    // Color is applied as the left side bar on each card.
    const NODE_BAR_COLORS = {
        model_view:   '#3F7BD9',
        model_table:  '#7C5CE6',
        model:        '#3F7BD9',
        source:       '#3FB950',
        seed:         '#FF9800',
        snapshot:     '#9C27B0',
        exposure:     '#E91E63',
        test:         '#B39DDB',
        stub:         '#9E9E9E'
    };

    const CARD_WIDTH = 220;
    const CARD_HEIGHT = 56;
    const STUB_WIDTH = 110;
    const STUB_HEIGHT = 40;

    // Inline SVG icons keyed by visual variant.
    const ICONS = {
        source: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3.5" y="3" width="9" height="10" rx="1.5"/><path d="M7 8h3.5M9 6.5L10.5 8 9 9.5"/></svg>',
        view: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="3" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11"/><text x="8" y="11.5" font-size="5" font-weight="700" fill="currentColor" stroke="none" text-anchor="middle" font-family="-apple-system, sans-serif">V</text></svg>',
        table: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="3" width="11" height="10" rx="1.5"/><path d="M2.5 6.5h11M2.5 10h11M6 6.5V13M10 6.5V13"/></svg>',
        seed: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M8 13V7M5 9c0-1.5 1.3-3 3-3s3 1.5 3 3M4.5 13h7"/></svg>',
        snapshot: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2.5" y="4.5" width="11" height="8" rx="1.5"/><circle cx="8" cy="8.5" r="2"/><path d="M6 4.5l1-1h2l1 1"/></svg>',
        exposure: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2.5 13V8.5M6 13V5.5M9.5 13V7M13 13V3.5"/></svg>',
        test: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3.5 8.5l3 3 6-7"/></svg>'
    };

    function pickIconKey(node) {
        if (node.resourceType === 'source') return 'source';
        if (node.resourceType === 'seed') return 'seed';
        if (node.resourceType === 'snapshot') return 'snapshot';
        if (node.resourceType === 'exposure') return 'exposure';
        // model: pick by materialization
        var mat = (node.materialization || 'view').toLowerCase();
        if (mat === 'table' || mat === 'incremental' || mat === 'materialized_view') return 'table';
        return 'view';
    }

    function pickBarColor(node) {
        if (node.resourceType !== 'model') return NODE_BAR_COLORS[node.resourceType] || '#888';
        var mat = (node.materialization || 'view').toLowerCase();
        if (mat === 'table' || mat === 'incremental' || mat === 'materialized_view') return NODE_BAR_COLORS.model_table;
        return NODE_BAR_COLORS.model_view;
    }

    let cy = null;
    let lastCurrentNodeId = null;
    let currentLayoutDir = 'LR';
    let lastEdgeCurveStyle = null;
    let previousNodeIds = new Set();
    let nodeCards = {};
    let activeDrag = null;
    let cyEventsBound = false;
    let cyInteractionsBound = false;

    window.addEventListener('mousemove', function (e) {
        if (!activeDrag || !cy) return;
        var dx = e.clientX - activeDrag.startX;
        var dy = e.clientY - activeDrag.startY;
        if (!activeDrag.moved && Math.hypot(dx, dy) < 4) return;
        activeDrag.moved = true;
        var node = cy.getElementById(activeDrag.id);
        if (!node || !node.length) return;
        var rect = cy.container().getBoundingClientRect();
        var pan = cy.pan();
        var zoom = cy.zoom();
        var modelX = (e.clientX - rect.left - pan.x) / zoom;
        var modelY = (e.clientY - rect.top - pan.y) / zoom;
        node.position({ x: modelX, y: modelY });
    });
    var pendingClickTimer = null;
    var lastClickedId = null;

    function dimToNeighborhood(nodeId) {
        if (!cy) return;
        var center = cy.getElementById(nodeId);
        if (!center.length) return;
        var keep = center.union(center.predecessors()).union(center.successors());
        cy.elements().forEach(function (el) {
            var card = nodeCards[el.id()];
            if (keep.contains(el)) {
                el.removeClass('dimmed');
                if (card) card.classList.remove('dimmed');
            } else {
                el.addClass('dimmed');
                if (card) card.classList.add('dimmed');
            }
        });
        // Highlight the clicked card
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('selected'); });
        var clickedCard = nodeCards[nodeId];
        if (clickedCard) clickedCard.classList.add('selected');
    }

    function clearNeighborhoodDim() {
        if (!cy) return;
        cy.elements().removeClass('dimmed');
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('dimmed'); });
    }

    // Click on empty graph area clears dim
    document.getElementById('cy').addEventListener('click', function () {
        clearNeighborhoodDim();
    });

    window.addEventListener('mouseup', function () {
        if (!activeDrag) return;
        var d = activeDrag;
        activeDrag = null;
        if (d.card) d.card.style.cursor = '';
        if (d.moved) return;

        if (d.data.resourceType === 'stub') {
            sendToKotlin('expandRequest', { direction: d.data.stubDirection, boundaryNodeId: d.data.boundaryNodeId });
            return;
        }

        // Distinguish single vs double click manually
        if (pendingClickTimer && lastClickedId === d.data.id) {
            // Double click — focus (refresh graph + open file)
            clearTimeout(pendingClickTimer);
            pendingClickTimer = null;
            lastClickedId = null;
            clearNeighborhoodDim();
            sendToKotlin('nodeClick', { nodeId: d.data.id, resourceType: d.data.resourceType });
            return;
        }

        // Single click — dim everything except this node's ancestors/descendants,
        // and push docs for it without re-focusing the graph
        if (pendingClickTimer) clearTimeout(pendingClickTimer);
        lastClickedId = d.data.id;
        var nodeId = d.data.id;
        var resourceType = d.data.resourceType;
        pendingClickTimer = setTimeout(function () {
            pendingClickTimer = null;
            lastClickedId = null;
            dimToNeighborhood(nodeId);
            sendToKotlin('previewNode', { nodeId: nodeId, resourceType: resourceType });
        }, 260);
    });
    const tooltipEl = document.getElementById('tooltip');
    const loadingEl = document.getElementById('loading');
    const overlayEl = document.getElementById('node-overlay');

    function buildNodeCards() {
        // Clear existing cards
        Object.keys(nodeCards).forEach(function (id) {
            var el = nodeCards[id];
            if (el && el.parentNode) el.parentNode.removeChild(el);
        });
        nodeCards = {};

        cy.nodes().forEach(function (node) {
            var data = node.data();
            var card = document.createElement('div');
            card.className = 'card-node';
            card.dataset.id = data.id;

            if (data.resourceType === 'stub') {
                card.classList.add('stub');
                var name = document.createElement('div');
                name.className = 'card-name';
                name.textContent = data.name || '+ more';
                card.appendChild(name);
            } else {
                card.style.setProperty('--card-bar-color', data.barColor);

                var bar = document.createElement('div');
                bar.className = 'card-bar';
                card.appendChild(bar);

                var icon = document.createElement('div');
                icon.className = 'card-icon';
                icon.innerHTML = ICONS[data.iconKey] || ICONS.view;
                card.appendChild(icon);

                var text = document.createElement('div');
                text.className = 'card-text';
                var name2 = document.createElement('div');
                name2.className = 'card-name';
                name2.textContent = data.name;
                text.appendChild(name2);
                if (data.schema) {
                    var schema = document.createElement('div');
                    schema.className = 'card-schema';
                    schema.textContent = data.schema;
                    text.appendChild(schema);
                }
                card.appendChild(text);
            }

            if (data.isCurrent) card.classList.add('selected');

            // Drag + click handling — actual drag/up listeners are global (see below).
            card.addEventListener('mousedown', function (e) {
                if (e.button !== 0) return;
                e.preventDefault();
                e.stopPropagation();
                hideTooltip();
                activeDrag = { id: data.id, data: data, card: card, startX: e.clientX, startY: e.clientY, moved: false };
                card.style.cursor = 'grabbing';
            });
            card.addEventListener('mouseenter', function (e) {
                if (activeDrag) return;
                showTooltip({ x: e.clientX, y: e.clientY }, data);
            });
            card.addEventListener('mousemove', function (e) {
                if (activeDrag) return;
                moveTooltip({ x: e.clientX, y: e.clientY });
            });
            card.addEventListener('mouseleave', function () {
                hideTooltip();
            });

            overlayEl.appendChild(card);
            nodeCards[data.id] = card;
        });
        syncNodeCards();
    }

    var syncRaf = null;
    function syncNodeCards() {
        if (syncRaf) return;
        syncRaf = requestAnimationFrame(function () {
            syncRaf = null;
            if (!cy) return;
            var zoom = cy.zoom();
            cy.nodes().forEach(function (node) {
                var card = nodeCards[node.id()];
                if (!card) return;
                var pos = node.renderedPosition();
                var w = node.width();
                var h = node.height();
                var renderedW = w * zoom;
                var renderedH = h * zoom;
                var left = pos.x - renderedW / 2;
                var top = pos.y - renderedH / 2;
                card.style.width = w + 'px';
                card.style.height = h + 'px';
                card.style.transform = 'translate(' + left + 'px, ' + top + 'px) scale(' + zoom + ')';
            });
        });
    }

    function buildCyStyles(edgeCurveStyle) {
        var cs = edgeCurveStyle || 'bezier';
        var edgeStyle = {
            'width': 1.5,
            'line-color': '#999',
            'target-arrow-color': '#999',
            'target-arrow-shape': 'triangle',
            'curve-style': cs,
            'arrow-scale': 0.8
        };
        if (cs === 'taxi' || cs === 'round-taxi') {
            edgeStyle['taxi-turn'] = 'data(taxiTurn)';
            edgeStyle['taxi-turn-min-distance'] = 0;
            edgeStyle['taxi-direction'] = 'auto';
        }
        return [
            {
                selector: 'node',
                style: {
                    'label': '',
                    'background-opacity': 0,
                    'border-width': 0,
                    'width': 'data(w)',
                    'height': 'data(h)',
                    'shape': 'round-rectangle'
                }
            },
            { selector: 'edge', style: edgeStyle },
            { selector: 'node.dimmed', style: { 'opacity': 0.25 } },
            { selector: 'edge.dimmed', style: { 'opacity': 0.15 } },
            {
                selector: 'edge.stub-edge',
                style: {
                    'width': 1,
                    'line-style': 'dashed',
                    'line-color': '#aaa',
                    'target-arrow-color': '#aaa'
                }
            }
        ];
    }

    function applyZoom(factor, x, y) {
        if (!cy) return;
        var zoom = cy.zoom() * factor;
        zoom = Math.max(cy.minZoom(), Math.min(cy.maxZoom(), zoom));
        cy.zoom({ level: zoom, renderedPosition: { x: x, y: y } });
    }

    function bindCyInteractionsOnce() {
        if (cyInteractionsBound) return;
        cyInteractionsBound = true;

        var cyContainer = document.getElementById('cy');
        cyContainer.addEventListener('wheel', function (e) {
            if (!cy) return;
            e.preventDefault();
            var delta = e.deltaY;
            var sensitivity = e.ctrlKey ? 0.01 : 0.001;
            applyZoom(1 - delta * sensitivity, e.offsetX, e.offsetY);
        }, { passive: false });

        document.getElementById('zoom-in').addEventListener('click', function () {
            if (!cy) return;
            applyZoom(1.02, cy.width() / 2, cy.height() / 2);
        });
        document.getElementById('zoom-out').addEventListener('click', function () {
            if (!cy) return;
            applyZoom(1 / 1.02, cy.width() / 2, cy.height() / 2);
        });
        document.getElementById('zoom-fit').addEventListener('click', function () {
            if (!cy) return;
            cy.fit(undefined, 30);
        });

        document.addEventListener('keydown', function (e) {
            if (!cy) return;
            if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
            var cx = cy.width() / 2;
            var cy2 = cy.height() / 2;
            if (e.key === '+' || e.key === '=' || (e.key === '=' && e.metaKey)) {
                e.preventDefault();
                applyZoom(1.02, cx, cy2);
            } else if (e.key === '-' || e.key === '_') {
                e.preventDefault();
                applyZoom(1 / 1.02, cx, cy2);
            } else if (e.key === '0') {
                e.preventDefault();
                cy.fit(undefined, 30);
            }
        });
    }

    function bindCyEventsOnce() {
        if (cyEventsBound || !cy) return;
        cyEventsBound = true;

        cy.on('tap', 'node', function (evt) {
            const data = evt.target.data();
            if (data.resourceType === 'stub') {
                sendToKotlin('expandRequest', { direction: data.stubDirection, boundaryNodeId: data.boundaryNodeId });
            } else {
                sendToKotlin('nodeClick', { nodeId: data.id, resourceType: data.resourceType });
            }
        });

        cy.on('mouseover', 'node', function (evt) {
            showTooltip(evt.renderedPosition, evt.target.data());
        });
        cy.on('mouseout', 'node', function () {
            hideTooltip();
        });
        cy.on('mousemove', 'node', function (evt) {
            moveTooltip(evt.renderedPosition);
        });
        cy.on('pan zoom position layoutstop', syncNodeCards);
    }

    function ensureCy(edgeCurveStyle) {
        if (cy) return;
        loadingEl.style.display = 'none';
        cy = cytoscape({
            container: document.getElementById('cy'),
            elements: [],
            layout: false,
            style: buildCyStyles(edgeCurveStyle),
            minZoom: 0.2,
            maxZoom: 3,
            zoomingEnabled: true,
            userZoomingEnabled: false
        });
        bindCyEventsOnce();
        bindCyInteractionsOnce();
        lastEdgeCurveStyle = edgeCurveStyle;
    }

    function updateEdgeCurveStyle(edgeCurveStyle) {
        if (!cy) return;
        var cs = edgeCurveStyle || 'bezier';
        var edgeStyle = {
            'width': 1.5,
            'line-color': '#999',
            'target-arrow-color': '#999',
            'target-arrow-shape': 'triangle',
            'curve-style': cs,
            'arrow-scale': 0.8
        };
        if (cs === 'taxi' || cs === 'round-taxi') {
            edgeStyle['taxi-turn'] = 'data(taxiTurn)';
            edgeStyle['taxi-turn-min-distance'] = 0;
            edgeStyle['taxi-direction'] = 'auto';
        }
        cy.style().selector('edge').style(edgeStyle).update();
    }

    function runDagreLayout(layoutDirection, onDone) {
        var layout = cy.layout({
            name: 'dagre',
            rankDir: layoutDirection || 'LR',
            nodeSep: 40,
            rankSep: 80,
            edgeSep: 20,
            animate: false,
            fit: false
        });
        if (onDone) {
            cy.one('layoutstop', onDone);
        }
        layout.run();
    }

    function splitElements(elements) {
        var nodes = [];
        var edges = [];
        for (var i = 0; i < elements.length; i++) {
            var el = elements[i];
            if (el.data && el.data.source) {
                edges.push(el);
            } else {
                nodes.push(el);
            }
        }
        return { nodes: nodes, edges: edges };
    }

    function setsDiffer(prev, next) {
        if (prev.size !== next.size) return true;
        var it = next.values();
        var step = it.next();
        while (!step.done) {
            if (!prev.has(step.value)) return true;
            step = it.next();
        }
        return false;
    }

    function updateCurrentHighlight(currentNodeId) {
        if (!cy) return;
        cy.nodes().forEach(function (node) {
            var isCur = node.id() === currentNodeId;
            node.data('isCurrent', isCur);
            var card = nodeCards[node.id()];
            if (card) {
                if (isCur) card.classList.add('selected');
                else card.classList.remove('selected');
            }
        });
    }

    function animateNewElements(prevNodeIds) {
        if (!cy || !prevNodeIds || prevNodeIds.size === 0) return;
        cy.nodes().forEach(function (node) {
            if (!prevNodeIds.has(node.id())) {
                node.style('opacity', 0);
                node.animate({ style: { opacity: 1 } }, {
                    duration: 300,
                    complete: function () { node.removeStyle('opacity'); }
                });
            }
        });
        cy.edges().forEach(function (edge) {
            var srcNew = !prevNodeIds.has(edge.source().id());
            var tgtNew = !prevNodeIds.has(edge.target().id());
            if (srcNew || tgtNew) {
                edge.style('opacity', 0);
                edge.animate({ style: { opacity: 1 } }, {
                    duration: 300,
                    complete: function () { edge.removeStyle('opacity'); }
                });
            }
        });
    }

    function applyGraphUpdate(elements, currentNodeId, edgeCurveStyle, layoutDirection) {
        var layoutDir = layoutDirection || 'LR';
        var curveStyle = edgeCurveStyle || 'bezier';

        if (!cy) {
            ensureCy(curveStyle);
            previousNodeIds = new Set();
            cy.add(elements);
            lastCurrentNodeId = currentNodeId;
            currentLayoutDir = layoutDir;
            runDagreLayout(layoutDir, function () {
                buildNodeCards();
                cy.fit(undefined, 30);
                var currentNode = cy.getElementById(currentNodeId);
                if (currentNode.length) cy.center(currentNode);
            });
            return;
        }

        var savedZoom = cy.zoom();
        var savedPan = cy.pan();
        var sameFocus = lastCurrentNodeId === currentNodeId;
        var layoutDirChanged = currentLayoutDir !== layoutDir;
        var curveChanged = lastEdgeCurveStyle !== curveStyle;
        currentLayoutDir = layoutDir;
        lastEdgeCurveStyle = curveStyle;

        var split = splitElements(elements);
        var newNodeIds = new Set(split.nodes.map(function (n) { return n.data.id; }));
        var newEdgeIds = new Set(split.edges.map(function (e) { return e.data.id; }));

        var prevNodeIds = new Set();
        cy.nodes().forEach(function (n) { prevNodeIds.add(n.id()); });
        var prevEdgeIds = new Set();
        cy.edges().forEach(function (e) { prevEdgeIds.add(e.id()); });

        var topologyChanged = layoutDirChanged ||
            setsDiffer(prevNodeIds, newNodeIds) ||
            setsDiffer(prevEdgeIds, newEdgeIds);

        if (curveChanged) {
            updateEdgeCurveStyle(curveStyle);
        }

        if (!topologyChanged) {
            cy.batch(function () {
                split.nodes.forEach(function (el) {
                    var node = cy.getElementById(el.data.id);
                    if (node.length) node.data(el.data);
                });
            });
            updateCurrentHighlight(currentNodeId);
            lastCurrentNodeId = currentNodeId;
            syncNodeCards();
            return;
        }

        previousNodeIds = prevNodeIds;

        cy.batch(function () {
            cy.nodes().forEach(function (n) {
                if (!newNodeIds.has(n.id())) n.remove();
            });
            cy.edges().forEach(function (e) {
                if (!newEdgeIds.has(e.id())) e.remove();
            });
            split.nodes.forEach(function (el) {
                var existing = cy.getElementById(el.data.id);
                if (existing.length) existing.data(el.data);
                else cy.add(el);
            });
            split.edges.forEach(function (el) {
                if (!cy.getElementById(el.data.id).length) cy.add(el);
            });
        });

        Object.keys(nodeCards).forEach(function (id) {
            if (!newNodeIds.has(id)) {
                var card = nodeCards[id];
                if (card && card.parentNode) card.parentNode.removeChild(card);
                delete nodeCards[id];
            }
        });

        lastCurrentNodeId = currentNodeId;

        runDagreLayout(layoutDir, function () {
            buildNodeCards();
            if (sameFocus) {
                cy.zoom(savedZoom);
                cy.pan(savedPan);
            } else {
                var currentNode = cy.getElementById(currentNodeId);
                if (currentNode.length) cy.center(currentNode);
            }
            animateNewElements(previousNodeIds);
        });
    }


    function showTooltip(pos, data) {
        var html = '<div class="tt-name">' + escapeHtml(data.label) + '</div>';
        html += '<div class="tt-detail">';
        html += 'Type: ' + data.resourceType;
        if (data.schema) html += '<br>Schema: ' + escapeHtml(data.schema);
        if (data.database) html += '<br>Database: ' + escapeHtml(data.database);
        if (data.materialization) html += '<br>Materialization: ' + data.materialization;
        if (data.description) html += '<br>' + escapeHtml(data.description.substring(0, 150));
        html += '</div>';
        tooltipEl.innerHTML = html;
        tooltipEl.style.display = 'block';
        moveTooltip(pos);
    }

    function moveTooltip(pos) {
        tooltipEl.style.left = (pos.x + 15) + 'px';
        tooltipEl.style.top = (pos.y + 15) + 'px';
    }

    function hideTooltip() {
        tooltipEl.style.display = 'none';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    // === Public API (called from Kotlin) ===

    function decodeBase64Utf8(b64) {
        var binary = atob(b64);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        return new TextDecoder('utf-8').decode(bytes);
    }

    /** Large payloads from Kotlin (base64 UTF-8 JSON — avoids escaping huge strings in executeJavaScript). */
    window.__dbtJsonDeliver = function (fn, b64) {
        var data = JSON.parse(decodeBase64Utf8(b64));
        if (fn === 'renderGraph') window.renderGraph(data);
        else if (fn === 'showDocs') window.showDocs(data);
    };

    window.renderGraph = function (jsonStr) {
        try {
            const graph = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            const elements = [];

            for (const node of graph.nodes) {
                var name = node.name;
                var w = CARD_WIDTH;
                var h = CARD_HEIGHT;
                if (node.resourceType === 'stub') { w = STUB_WIDTH; h = STUB_HEIGHT; }

                elements.push({
                    data: {
                        id: node.id,
                        name: name,
                        resourceType: node.resourceType,
                        schema: node.schema,
                        database: node.database,
                        materialization: node.materialization,
                        description: node.description,
                        filePath: node.filePath,
                        depth: node.depth,
                        isCurrent: node.isCurrent,
                        stubDirection: node.stubDirection,
                        boundaryNodeId: node.boundaryNodeId,
                        iconKey: pickIconKey(node),
                        barColor: pickBarColor(node),
                        w: w,
                        h: h
                    }
                });
            }

            // Count siblings per source/target so we can offset taxi-turn per-edge
            var sourceCounts = {};
            var targetCounts = {};
            for (const e of graph.edges) {
                sourceCounts[e.fromNodeId] = (sourceCounts[e.fromNodeId] || 0) + 1;
                targetCounts[e.toNodeId] = (targetCounts[e.toNodeId] || 0) + 1;
            }
            var sourceIdx = {};
            var targetIdx = {};

            for (const edge of graph.edges) {
                var isStub = edge.fromNodeId.indexOf('__stub_') === 0 || edge.toNodeId.indexOf('__stub_') === 0;

                // Spread parallel edges by varying taxi-turn as a percentage of the rank span.
                // Stays within [20%, 80%] so the bend never lands past either endpoint.
                var sIdx = sourceIdx[edge.fromNodeId] = (sourceIdx[edge.fromNodeId] || 0) + 1;
                var tIdx = targetIdx[edge.toNodeId] = (targetIdx[edge.toNodeId] || 0) + 1;
                var sCount = sourceCounts[edge.fromNodeId];
                var tCount = targetCounts[edge.toNodeId];

                var turnPct = 50;
                if (sCount > 1 || tCount > 1) {
                    var fromSrc = sCount > 1 ? (sIdx / (sCount + 1)) : 0.5;
                    var fromTgt = tCount > 1 ? (tIdx / (tCount + 1)) : 0.5;
                    // Blend, weight source-spread more (bend originates there)
                    var blend = (fromSrc * 0.7 + fromTgt * 0.3);
                    turnPct = Math.round(20 + blend * 60); // 20..80
                }

                elements.push({
                    data: {
                        id: edge.fromNodeId + '->' + edge.toNodeId,
                        source: edge.fromNodeId,
                        target: edge.toNodeId,
                        taxiTurn: turnPct + '%'
                    },
                    classes: isStub ? 'stub-edge' : ''
                });
            }

            applyGraphUpdate(elements, graph.currentNodeId, graph.edgeCurveStyle, graph.layoutDirection);
        } catch (e) {
            console.error('renderGraph error:', e);
        }
    };

    window.highlightNode = function (nodeId) {
        if (!cy) return;
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('selected'); });
        const node = cy.getElementById(nodeId);
        if (node.length) {
            var card = nodeCards[nodeId];
            if (card) card.classList.add('selected');
            cy.animate({ center: { eles: node }, duration: 300 });
        }
    };

    window.filterNodes = function (query) {
        if (!cy) return;
        if (!query || query.trim() === '') {
            resetFilter();
            return;
        }
        const q = query.toLowerCase();
        cy.nodes().forEach(function (node) {
            var name = (node.data('name') || '').toLowerCase();
            var id = (node.data('id') || '').toLowerCase();
            var match = name.includes(q) || id.includes(q);
            if (match) {
                node.removeClass('dimmed');
                var c = nodeCards[node.id()]; if (c) c.classList.remove('dimmed');
            } else {
                node.addClass('dimmed');
                var c2 = nodeCards[node.id()]; if (c2) c2.classList.add('dimmed');
            }
        });
        cy.edges().forEach(function (edge) {
            const src = edge.source();
            const tgt = edge.target();
            if (src.hasClass('dimmed') && tgt.hasClass('dimmed')) {
                edge.addClass('dimmed');
            } else {
                edge.removeClass('dimmed');
            }
        });
    };

    window.resetFilter = function () {
        if (!cy) return;
        cy.elements().removeClass('dimmed');
        Object.values(nodeCards).forEach(function (c) { c.classList.remove('dimmed'); });
    };

    // === Docs sidebar ===

    var sidebarEl = document.getElementById('docs-sidebar');
    var sidebarToggleBtn = document.getElementById('toggle-sidebar');
    var sidebarCloseBtn = document.getElementById('docs-close');
    var lastDocsPayload = null;

    function setSidebarOpen(open) {
        if (!sidebarEl) return;
        sidebarEl.classList.toggle('open', open);
        if (sidebarToggleBtn) sidebarToggleBtn.classList.toggle('active', open);
    }

    if (sidebarToggleBtn) {
        sidebarToggleBtn.addEventListener('click', function () {
            setSidebarOpen(!sidebarEl.classList.contains('open'));
        });
    }

    var regenerateBtn = document.getElementById('regenerate-docs');
    if (regenerateBtn) {
        regenerateBtn.addEventListener('click', function () {
            if (regenerateBtn.classList.contains('running')) return;
            regenerateBtn.classList.add('running');
            regenerateBtn.classList.remove('needs-attention');
            sendToKotlin('regenerateDocs', {});
        });
    }
    window.setRegenerateNeedsAttention = function (needs) {
        if (regenerateBtn) regenerateBtn.classList.toggle('needs-attention', !!needs);
    };
    window.setRegenerateRunning = function (running) {
        if (regenerateBtn) regenerateBtn.classList.toggle('running', !!running);
    };
    if (sidebarCloseBtn) {
        sidebarCloseBtn.addEventListener('click', function () { setSidebarOpen(false); });
    }

    // Restore persisted width
    try {
        var savedW = localStorage.getItem('dbtHelper.sidebarWidth');
        if (savedW && sidebarEl) sidebarEl.style.width = savedW + 'px';
    } catch (e) {}

    // Apply wheel deltas directly + stop bubbling so the cytoscape canvas
    // beneath the sidebar doesn't waste compositing time on each event.
    (function () {
        var contentEl = document.querySelector('.docs-content');
        if (!contentEl || !sidebarEl) return;

        // Stop wheel propagation at the sidebar boundary
        sidebarEl.addEventListener('wheel', function (e) {
            e.stopPropagation();
        }, { capture: true });

        contentEl.addEventListener('wheel', function (e) {
            if (e.ctrlKey) return;
            var dy = e.deltaY;
            if (e.deltaMode === 1) dy *= 16;
            else if (e.deltaMode === 2) dy *= contentEl.clientHeight;
            contentEl.scrollTop += dy;
            e.preventDefault();
        }, { passive: false });
    })();



    // Resize handle drag
    var resizeHandle = document.getElementById('docs-resize');
    var resizing = null;
    if (resizeHandle) {
        resizeHandle.addEventListener('mousedown', function (e) {
            e.preventDefault();
            var rect = sidebarEl.getBoundingClientRect();
            resizing = { startX: e.clientX, startW: rect.width };
            document.body.style.cursor = 'ew-resize';
            document.body.style.userSelect = 'none';
        });
    }
    window.addEventListener('mousemove', function (e) {
        if (!resizing) return;
        var dx = resizing.startX - e.clientX;
        var newW = resizing.startW + dx;
        var minW = 280;
        var maxW = window.innerWidth * 0.8;
        newW = Math.max(minW, Math.min(maxW, newW));
        sidebarEl.style.width = newW + 'px';
        if (cy) cy.resize();
    });
    window.addEventListener('mouseup', function () {
        if (!resizing) return;
        try {
            var w = sidebarEl.getBoundingClientRect().width;
            localStorage.setItem('dbtHelper.sidebarWidth', String(Math.round(w)));
        } catch (e) {}
        resizing = null;
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
    });

    Array.prototype.forEach.call(document.querySelectorAll('.docs-tab'), function (tab) {
        tab.addEventListener('click', function () {
            var which = tab.dataset.tab;
            Array.prototype.forEach.call(document.querySelectorAll('.docs-tab'), function (t) {
                t.classList.toggle('active', t === tab);
            });
            Array.prototype.forEach.call(document.querySelectorAll('.docs-section'), function (s) {
                s.classList.toggle('active', s.id === 'docs-section-' + which);
            });
        });
    });

    function renderDocsHeader(p) {
        var iconEl = document.getElementById('docs-icon');
        var iconKey = pickIconKey({
            resourceType: p.kind,
            materialization: p.materialization
        });
        iconEl.innerHTML = ICONS[iconKey] || ICONS.view;
        document.getElementById('docs-name').textContent = p.name || '';
        document.getElementById('docs-schema').textContent = p.schema || '';
    }

    function renderDocsPills(p) {
        var pillsEl = document.getElementById('docs-pills');
        pillsEl.innerHTML = '';
        function add(text, cls) {
            var el = document.createElement('span');
            el.className = 'docs-pill' + (cls ? ' ' + cls : '');
            el.textContent = text;
            pillsEl.appendChild(el);
        }
        if (p.materialization) add(p.materialization);
        // Skip the incremental flag pill when materialization already says "incremental"
        if (p.kind === 'model' && p.materialization !== 'incremental') {
            add('incremental: ' + (p.incremental ? 'true' : 'false'));
        }
        var total = (p.tests && p.tests.total) || 0;
        if (total > 0) add(total + '/' + total + ' tests', 'tests-ok');
        var tags = (p.metadata && p.metadata.tags) || [];
        for (var i = 0; i < tags.length; i++) add('#' + tags[i], 'tag');
    }

    function renderDocsDescription(p) {
        var wrap = document.getElementById('docs-description');
        var textEl = document.getElementById('docs-desc-text');
        var desc = (p.metadata && p.metadata.description) || '';
        wrap.classList.toggle('empty', !desc);
        textEl.textContent = desc;
        // Default: expanded
        wrap.classList.add('expanded');
        textEl.classList.remove('collapsed');
        if (!desc) return;
        // Determine whether the toggle button is needed (multi-line / overflows when collapsed)
        requestAnimationFrame(function () {
            var hasMultiline = desc.indexOf('\n') >= 0;
            // Probe collapsed width by temporarily measuring
            textEl.classList.add('collapsed');
            var hasOverflow = textEl.scrollWidth > textEl.clientWidth + 1;
            textEl.classList.remove('collapsed');
            wrap.classList.toggle('has-overflow', hasMultiline || hasOverflow);
        });
    }

    var descToggleBtn = document.getElementById('docs-desc-toggle');
    if (descToggleBtn) {
        descToggleBtn.addEventListener('click', function () {
            var wrap = document.getElementById('docs-description');
            var textEl = document.getElementById('docs-desc-text');
            var expanded = wrap.classList.toggle('expanded');
            textEl.classList.toggle('collapsed', !expanded);
            descToggleBtn.title = expanded ? 'Collapse description' : 'Expand description';
        });
    }

    function renderDocsColumns(p) {
        var el = document.getElementById('docs-section-columns');
        var cols = (p.columns || []);
        if (!cols.length) { el.innerHTML = '<div class="empty">No columns documented.</div>'; return; }
        var html = '<div class="col-header"><span>Column</span><span>Type</span></div>';
        for (var i = 0; i < cols.length; i++) {
            var c = cols[i];
            html += '<div class="col-row">';
            html += '<div class="col-main">';
            html += '<div class="col-name">';
            if (c.isPrimaryKey) html += '<span class="col-pk-badge">PK</span>';
            html += escapeHtml(c.name);
            html += '</div>';
            if (c.fk) html += '<div class="col-fk">FK → ' + escapeHtml(c.fk) + '</div>';
            if (c.description) html += '<div class="col-desc">' + escapeHtml(c.description) + '</div>';
            html += '</div>';
            html += '<div class="col-type">' + escapeHtml(c.type || '') + '</div>';
            html += '</div>';
        }
        el.innerHTML = html;
    }

    function renderDocsTests(p) {
        var el = document.getElementById('docs-section-tests');
        var list = (p.tests && p.tests.list) || [];
        if (!list.length) { el.innerHTML = '<div class="empty">No tests defined.</div>'; return; }
        var html = '<ul class="docs-list">';
        for (var i = 0; i < list.length; i++) {
            var t = list[i];
            html += '<li>' + escapeHtml(t.shortName);
            if (t.column) html += ' <span style="color: var(--card-schema)">on ' + escapeHtml(t.column) + '</span>';
            html += '</li>';
        }
        html += '</ul>';
        el.innerHTML = html;
    }

    function renderDocsSql(p) {
        var el = document.getElementById('docs-section-sql');
        var sql = (p.sql && (p.sql.raw || p.sql.compiled)) || '';
        if (!sql) { el.innerHTML = '<div class="empty">No SQL available.</div>'; return; }
        el.innerHTML = '<pre class="code-block">' + escapeHtml(sql) + '</pre>';
    }

    function renderDocsMetadata(p) {
        var el = document.getElementById('docs-section-metadata');
        var m = p.metadata || {};
        var rows = [];
        if (m.fullName) rows.push(['Relation', m.fullName]);
        if (m.filePath) rows.push(['File', m.filePath]);
        if (m.patchPath) rows.push(['Docs', m.patchPath]);
        if (m.packageName) rows.push(['Package', m.packageName]);
        if (m.fqn && m.fqn.length) rows.push(['FQN', m.fqn.join(' → ')]);
        if (m.tags && m.tags.length) rows.push(['Tags', m.tags.join(', ')]);
        if (m.description) rows.push(['Description', m.description]);
        if (m.dependsOn && m.dependsOn.length) rows.push(['Depends on', m.dependsOn.join(', ')]);
        if (m.referencedBy && m.referencedBy.length) rows.push(['Referenced by', m.referencedBy.join(', ')]);
        if (m.config) {
            Object.keys(m.config).forEach(function (k) { rows.push([k, m.config[k]]); });
        }
        if (m.loader) rows.push(['Loader', m.loader]);
        if (m.loadedAtField) rows.push(['Loaded at', m.loadedAtField]);
        if (m.freshnessWarnAfter) rows.push(['Warn after', m.freshnessWarnAfter]);
        if (m.freshnessErrorAfter) rows.push(['Error after', m.freshnessErrorAfter]);
        if (!rows.length) { el.innerHTML = '<div class="empty">No metadata.</div>'; return; }
        var html = '<table class="meta-table">';
        for (var i = 0; i < rows.length; i++) {
            html += '<tr><td class="k">' + escapeHtml(rows[i][0]) + '</td><td class="v">' + escapeHtml(String(rows[i][1])) + '</td></tr>';
        }
        html += '</table>';
        el.innerHTML = html;
    }

    window.showDocs = function (jsonStr) {
        try {
            var p = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            lastDocsPayload = p;
            renderDocsHeader(p);
            renderDocsDescription(p);
            renderDocsPills(p);
            renderDocsColumns(p);
            renderDocsTests(p);
            renderDocsSql(p);
            renderDocsMetadata(p);
        } catch (e) {
            console.error('showDocs error:', e);
        }
    };

    window.applyTheme = function (isDark) {
        var root = document.documentElement;
        document.body.classList.toggle('theme-light', !isDark);
        if (isDark) {
            root.style.setProperty('--bg-color', '#1e1e1e');
            root.style.setProperty('--text-color', '#ccc');
            root.style.setProperty('--tooltip-bg', '#2d2d2d');
            root.style.setProperty('--tooltip-border', '#555');
            root.style.setProperty('--tooltip-text', '#ddd');
            root.style.setProperty('--tooltip-name', '#fff');
            root.style.setProperty('--tooltip-detail', '#aaa');
            root.style.setProperty('--btn-bg', '#2d2d2d');
            root.style.setProperty('--btn-border', '#555');
            root.style.setProperty('--btn-text', '#ccc');
            root.style.setProperty('--btn-hover', '#3d3d3d');
            root.style.setProperty('--edge-color', '#555');
            root.style.setProperty('--loading-color', '#888');
        } else {
            root.style.setProperty('--bg-color', '#f5f5f5');
            root.style.setProperty('--text-color', '#333');
            root.style.setProperty('--tooltip-bg', '#fff');
            root.style.setProperty('--tooltip-border', '#ccc');
            root.style.setProperty('--tooltip-text', '#333');
            root.style.setProperty('--tooltip-name', '#111');
            root.style.setProperty('--tooltip-detail', '#666');
            root.style.setProperty('--btn-bg', '#fff');
            root.style.setProperty('--btn-border', '#ccc');
            root.style.setProperty('--btn-text', '#333');
            root.style.setProperty('--btn-hover', '#e8e8e8');
            root.style.setProperty('--edge-color', '#999');
            root.style.setProperty('--loading-color', '#666');
        }
        // Update edge colors in cytoscape if graph exists
        if (cy) {
            var edgeColor = isDark ? '#555' : '#999';
            cy.edges().style({ 'line-color': edgeColor, 'target-arrow-color': edgeColor });
        }
    };

    // === Bridge to Kotlin ===

    function sendToKotlin(type, payload) {
        const request = JSON.stringify({ type: type, payload: payload });
        if (window.__cefQueryBridge) {
            window.__cefQueryBridge(request);
        }
    }

    // Search input
    var searchInput = document.getElementById('search-input');
    var searchClear = document.getElementById('search-clear');
    var searchDebounce = null;

    function updateClearButton() {
        searchClear.style.display = searchInput.value.length > 0 ? 'block' : 'none';
    }

    searchInput.addEventListener('input', function (e) {
        updateClearButton();
        clearTimeout(searchDebounce);
        searchDebounce = setTimeout(function () {
            window.filterNodes(e.target.value);
        }, 150);
    });

    searchClear.addEventListener('click', function () {
        searchInput.value = '';
        updateClearButton();
        window.resetFilter();
        searchInput.focus();
    });

    // Signal ready
    window.addEventListener('DOMContentLoaded', function () {
        setTimeout(function () {
            sendToKotlin('ready', {});
        }, 100);
    });
})();
