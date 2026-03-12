// dbt Lineage Graph — Cytoscape.js + Dagre
(function () {
    'use strict';

    const NODE_COLORS = {
        model:    { bg: '#2196F3', border: '#1565C0', text: '#fff' },
        source:   { bg: '#4CAF50', border: '#2E7D32', text: '#fff' },
        seed:     { bg: '#FF9800', border: '#E65100', text: '#fff' },
        snapshot: { bg: '#9C27B0', border: '#6A1B9A', text: '#fff' },
        exposure: { bg: '#E91E63', border: '#AD1457', text: '#fff' },
        test:     { bg: '#B39DDB', border: '#B39DDB', text: '#333' },
        stub:     { bg: '#9E9E9E', border: '#9E9E9E', text: '#fff' }
    };

    const CURRENT_BORDER = '#FFD600';

    let cy = null;
    let lastCurrentNodeId = null;
    let currentLayoutDir = 'LR';
    let previousNodeIds = new Set();
    const tooltipEl = document.getElementById('tooltip');
    const loadingEl = document.getElementById('loading');

    function initCytoscape(elements, currentNodeId, edgeCurveStyle, layoutDirection) {
        currentLayoutDir = layoutDirection || 'LR';
        // Save viewport if re-rendering
        var savedZoom = null;
        var savedPan = null;
        var isRerender = cy && lastCurrentNodeId === currentNodeId;
        if (isRerender) {
            savedZoom = cy.zoom();
            savedPan = cy.pan();
        }
        if (cy) {
            previousNodeIds = new Set();
            cy.nodes().forEach(function (n) { previousNodeIds.add(n.id()); });
            cy.destroy();
        }
        lastCurrentNodeId = currentNodeId;
        loadingEl.style.display = 'none';

        cy = cytoscape({
            container: document.getElementById('cy'),
            elements: elements,
            layout: {
                name: 'dagre',
                rankDir: layoutDirection || 'LR',
                nodeSep: 40,
                rankSep: 80,
                edgeSep: 20,
                animate: false,
                fit: false
            },
            style: [
                {
                    selector: 'node',
                    style: {
                        'label': 'data(label)',
                        'text-valign': 'center',
                        'text-halign': 'center',
                        'font-size': '11px',
                        'font-family': '-apple-system, BlinkMacSystemFont, system-ui, sans-serif',
                        'color': 'data(textColor)',
                        'background-color': 'data(bgColor)',
                        'border-width': 0,
                        'width': 'label',
                        'height': 32,
                        'padding': '10px',
                        'shape': 'data(shape)',
                        'text-wrap': 'ellipsis',
                        'text-max-width': '140px'
                    }
                },
                {
                    selector: 'node[?isCurrent]',
                    style: {
                        'border-color': CURRENT_BORDER,
                        'border-width': 3,
                        'font-weight': 'bold',
                        'border-opacity': 1
                    }
                },
                {
                    selector: 'edge',
                    style: {
                        'width': 1.5,
                        'line-color': '#999',
                        'target-arrow-color': '#999',
                        'target-arrow-shape': 'triangle',
                        'curve-style': edgeCurveStyle || 'bezier',
                        'arrow-scale': 0.8
                    }
                },
                {
                    selector: 'node.dimmed',
                    style: { 'opacity': 0.25 }
                },
                {
                    selector: 'edge.dimmed',
                    style: { 'opacity': 0.15 }
                },
                {
                    selector: 'node.highlighted',
                    style: {
                        'border-color': CURRENT_BORDER,
                        'border-width': 3,
                        'border-opacity': 1
                    }
                },
                {
                    selector: 'node[resourceType="test"]',
                    style: {
                        'font-size': '8px',
                        'height': 18,
                        'padding': '4px',
                        'border-width': 0,
                        'text-max-width': '120px'
                    }
                },
                {
                    selector: 'edge.test-edge',
                    style: {
                        'width': 1,
                        'line-color': '#888',
                        'target-arrow-color': '#888',
                        'line-style': 'dashed'
                    }
                },
                {
                    selector: 'node[resourceType="stub"]',
                    style: {
                        'font-size': '10px',
                        'height': 26,
                        'padding': '8px',
                        'border-width': 1,
                        'border-style': 'dashed',
                        'border-color': '#999',
                        'cursor': 'pointer'
                    }
                },
                {
                    selector: 'edge.stub-edge',
                    style: {
                        'width': 1,
                        'line-style': 'dashed',
                        'line-color': '#aaa',
                        'target-arrow-color': '#aaa'
                    }
                }
            ],
            minZoom: 0.2,
            maxZoom: 3,
            zoomingEnabled: true,
            userZoomingEnabled: false
        });

        // Node click
        cy.on('tap', 'node', function (evt) {
            const data = evt.target.data();
            if (data.resourceType === 'stub') {
                sendToKotlin('expandRequest', { direction: data.stubDirection, boundaryNodeId: data.boundaryNodeId });
            } else {
                sendToKotlin('nodeClick', { nodeId: data.id, resourceType: data.resourceType });
            }
        });

        // Hover tooltip
        cy.on('mouseover', 'node', function (evt) {
            const data = evt.target.data();
            showTooltip(evt.renderedPosition, data);
        });

        cy.on('mouseout', 'node', function () {
            hideTooltip();
        });

        cy.on('mousemove', 'node', function (evt) {
            moveTooltip(evt.renderedPosition);
        });

        // Reposition test nodes below their parent model
        repositionTests();

        // Restore viewport or center on current node
        if (isRerender && savedZoom && savedPan) {
            cy.zoom(savedZoom);
            cy.pan(savedPan);
        } else {
            // First render — fit graph, then center on current node
            cy.fit(undefined, 30);
            var currentNode = cy.getElementById(currentNodeId);
            if (currentNode.length) {
                cy.center(currentNode);
            }
        }

        // Fade in new nodes and edges
        if (isRerender && previousNodeIds.size > 0) {
            cy.nodes().forEach(function (node) {
                if (!previousNodeIds.has(node.id())) {
                    node.style('opacity', 0);
                    node.animate({ style: { opacity: 1 } }, { duration: 300, complete: function () {
                        node.removeStyle('opacity');
                    }});
                }
            });
            cy.edges().forEach(function (edge) {
                var srcNew = !previousNodeIds.has(edge.source().id());
                var tgtNew = !previousNodeIds.has(edge.target().id());
                if (srcNew || tgtNew) {
                    edge.style('opacity', 0);
                    edge.animate({ style: { opacity: 1 } }, { duration: 300, complete: function () {
                        edge.removeStyle('opacity');
                    }});
                }
            });
        }

        // Manual wheel/pinch zoom for JCEF trackpad compatibility
        var cyContainer = document.getElementById('cy');

        function applyZoom(factor, x, y) {
            var zoom = cy.zoom() * factor;
            zoom = Math.max(cy.minZoom(), Math.min(cy.maxZoom(), zoom));
            cy.zoom({ level: zoom, renderedPosition: { x: x, y: y } });
        }

        // Wheel event — handles scroll and ctrl+scroll (pinch on some systems)
        cyContainer.addEventListener('wheel', function (e) {
            if (!cy) return;
            e.preventDefault();
            var delta = e.deltaY;
            var sensitivity = e.ctrlKey ? 0.01 : 0.001;
            applyZoom(1 - delta * sensitivity, e.offsetX, e.offsetY);
        }, { passive: false });

        // Zoom control buttons — 5% step
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

        // Keyboard zoom: +/- and =/- keys (skip when typing in search)
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

    function repositionTests() {
        if (!cy) return;
        var testNodes = cy.nodes('[resourceType="test"]');
        testNodes.forEach(function (testNode) {
            // Find the parent model — the source of the edge going into this test
            var incomers = testNode.incomers('node');
            if (incomers.length === 0) return;

            // Group tests by their parent model
            var parentNode = incomers[0];
            var parentPos = parentNode.position();

            // Count how many tests this parent already has positioned
            var siblingTests = parentNode.outgoers('node').filter('[resourceType="test"]');
            var idx = 0;
            siblingTests.forEach(function (sib, i) {
                if (sib.id() === testNode.id()) idx = i;
            });

            var isVertical = (currentLayoutDir === 'TB' || currentLayoutDir === 'BT');

            if (isVertical) {
                // Place tests to the right of parent
                var parentW = parentNode.outerWidth();
                var testW = testNode.outerWidth();
                var gap = 6;
                var offsetX = (parentW / 2) + testW / 2 + gap + (idx * (testW + gap));
                testNode.position({
                    x: parentPos.x + offsetX,
                    y: parentPos.y
                });
            } else {
                // Place tests below parent
                var parentW = parentNode.width();
                testNode.style('width', parentW * 0.8);
                var parentH = parentNode.outerHeight();
                var testH = testNode.outerHeight();
                var gap = 6;
                var offsetY = (parentH / 2) + testH / 2 + gap + (idx * (testH + gap));
                testNode.position({
                    x: parentPos.x,
                    y: parentPos.y + offsetY
                });
            }

            // Mark edges to tests
            testNode.connectedEdges().addClass('test-edge');
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

    window.renderGraph = function (jsonStr) {
        try {
            const graph = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
            const elements = [];

            const TYPE_ICONS = {
                model: '\u{1F4E6}', // 📦
                source: '\u{1F4E5}', // 📥
                seed: '\u{1F331}', // 🌱
                snapshot: '\u{1F4F7}', // 📷
                exposure: '\u{1F4CA}', // 📊
                test: '\u2714',       // ✔
                stub: '\u2026'        // …
            };

            for (const node of graph.nodes) {
                const colors = NODE_COLORS[node.resourceType] || NODE_COLORS.model;
                const shape = node.resourceType === 'exposure' ? 'diamond' :
                              node.resourceType === 'test' ? 'round-rectangle' : 'round-rectangle';
                var label = node.name;
                if (node.resourceType === 'test') {
                    var m = node.name.match(/^(unique|not_null|accepted_values|relationships|dbt_utils_\w+|dbt_expectations_\w+)/);
                    if (m) label = m[1];
                }
                var icon = TYPE_ICONS[node.resourceType] || '';
                if (icon && node.resourceType !== 'stub') {
                    label = icon + ' ' + label;
                }
                elements.push({
                    data: {
                        id: node.id,
                        label: label,
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
                        bgColor: colors.bg,
                        borderColor: colors.border,
                        textColor: colors.text,
                        shape: shape
                    }
                });
            }

            for (const edge of graph.edges) {
                var isStub = edge.fromNodeId.indexOf('__stub_') === 0 || edge.toNodeId.indexOf('__stub_') === 0;
                elements.push({
                    data: {
                        id: edge.fromNodeId + '->' + edge.toNodeId,
                        source: edge.fromNodeId,
                        target: edge.toNodeId
                    },
                    classes: isStub ? 'stub-edge' : ''
                });
            }

            initCytoscape(elements, graph.currentNodeId, graph.edgeCurveStyle, graph.layoutDirection);
        } catch (e) {
            console.error('renderGraph error:', e);
        }
    };

    window.highlightNode = function (nodeId) {
        if (!cy) return;
        cy.nodes().removeClass('highlighted');
        const node = cy.getElementById(nodeId);
        if (node.length) {
            node.addClass('highlighted');
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
            var label = (node.data('label') || '').toLowerCase();
            var id = (node.data('id') || '').toLowerCase();
            if (label.includes(q) || id.includes(q)) {
                node.removeClass('dimmed');
            } else {
                node.addClass('dimmed');
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
    };

    window.applyTheme = function (isDark) {
        var root = document.documentElement;
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
