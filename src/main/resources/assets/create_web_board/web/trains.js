/**
 * trains.js — front-end for the Create train dispatch dashboard.
 *
 * Single-page layout: SVG map on the left, collapsible sidebar sections on the right.
 * All former tabs (categories / lines / tags / routes / departures) are merged into the
 * sidebar as collapsible sections — they were display-only or single-purpose, so splitting
 * them into tabs added navigation overhead without clarity.
 *
 * Polls the REST API every 2s for live train + topology data. Route search uses CRN
 * station tags (not raw Create station names) when CRN is present. Departures auto-refresh
 * when their section is open.
 */
(function () {
    'use strict';

    // ---------- state ----------
    const state = {
        trains: [],
        graph: { nodes: [], edges: [], stations: [], graphId: '', lastUpdatedMs: 0, stale: true },
        categories: [],
        lines: [],
        tags: [],
        selectedTrainId: null,
    };

    const POLL_MS = 2000;
    let departuresTimer = null;
    let tagsLoaded = false;

    // ---------- helpers ----------
    function fetchJson(url) {
        return fetch(url).then(r => {
            if (!r.ok) throw new Error(url + ' → ' + r.status);
            return r.json();
        });
    }

    /** Convert an int RGB color (e.g. 0x3b82f6 or -1 for "default") to a CSS hex string. */
    function intToHex(c) {
        if (c == null || c < 0) return '#9aa0aa';
        return '#' + (c & 0xFFFFFF).toString(16).padStart(6, '0');
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, ch => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[ch]));
    }

    function fmtSpeed(s) {
        const abs = Math.abs(s);
        if (abs < 0.05) return '0';
        return abs.toFixed(2);
    }

    function fmtTime(ts) {
        if (!ts) return '--';
        const d = new Date(ts);
        return d.getHours().toString().padStart(2, '0') + ':' +
               d.getMinutes().toString().padStart(2, '0') + ':' +
               d.getSeconds().toString().padStart(2, '0');
    }

    // ---------- collapsible sections ----------
    document.querySelectorAll('.side-section > h3').forEach(h3 => {
        h3.addEventListener('click', () => {
            const section = h3.parentElement;
            const id = section.id;
            const willOpen = !section.classList.contains('open');
            section.classList.toggle('open', willOpen);
            if (willOpen) onSectionOpen(id);
            else onSectionClose(id);
        });
    });

    function onSectionOpen(id) {
        if (id === 'section-routes') populateRouteSelectors();
        else if (id === 'section-departures') {
            populateDepartureSelector();
            refreshDepartures();
            departuresTimer = setInterval(refreshDepartures, 5000);
        }
        else if (id === 'section-metadata') loadMetadata();
        else if (id === 'section-detail') renderTrainDetail();
    }

    function onSectionClose(id) {
        if (id === 'section-departures' && departuresTimer) {
            clearInterval(departuresTimer);
            departuresTimer = null;
        }
    }

    // ---------- live data polling ----------
    function pollLive() {
        Promise.all([fetchJson('/api/trains'), fetchJson('/api/trains/graph')])
            .then(([trains, graph]) => {
                state.trains = trains;
                state.graph = graph;
                renderMap();
                renderTrainList();
                renderStats();
            })
            .catch(err => console.warn('poll failed:', err));
    }

    function renderStats() {
        document.getElementById('stat-trains').textContent = state.trains.length;
        document.getElementById('stat-stations').textContent = state.graph.stations.length;
    }

    // ---------- SVG map ----------
    function renderMap() {
        const svg = document.getElementById('map-svg');
        const pane = document.getElementById('map-pane');
        const empty = document.getElementById('map-empty');
        const w = pane.clientWidth;
        const h = pane.clientHeight;

        const nodes = state.graph.nodes;
        const edges = state.graph.edges;
        const stations = state.graph.stations;
        const trains = state.trains;

        if (nodes.length === 0 && trains.length === 0) {
            svg.innerHTML = '';
            empty.style.display = 'flex';
            return;
        }
        empty.style.display = 'none';

        let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
        const allPoints = nodes.map(n => [n.x, n.z]).concat(trains.map(t => [t.x, t.z]));
        for (const [x, z] of allPoints) {
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        if (!isFinite(minX)) { minX = maxX = minZ = maxZ = 0; }
        const padX = Math.max(8, (maxX - minX) * 0.05);
        const padZ = Math.max(8, (maxZ - minZ) * 0.05);
        minX -= padX; maxX += padX; minZ -= padZ; maxZ += padZ;
        const spanX = Math.max(1, maxX - minX);
        const spanZ = Math.max(1, maxZ - minZ);
        const scale = Math.min(w / spanX, h / spanZ);
        const offX = (w - spanX * scale) / 2;
        const offZ = (h - spanZ * scale) / 2;
        function proj(x, z) {
            return [offX + (x - minX) * scale, offZ + (z - minZ) * scale];
        }

        let html = '';
        for (const e of edges) {
            const [x1, y1] = proj(e.a.x, e.a.z);
            const [x2, y2] = proj(e.b.x, e.b.z);
            html += `<line class="map-edge" x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}"/>`;
        }
        for (const s of stations) {
            const [sx, sy] = proj(s.x, s.z);
            html += `<circle class="map-station" cx="${sx.toFixed(1)}" cy="${sy.toFixed(1)}" r="4"><title>${escapeHtml(s.name)}</title></circle>`;
            html += `<text class="map-station-label" x="${(sx + 6).toFixed(1)}" y="${(sy + 3).toFixed(1)}">${escapeHtml(s.name)}</text>`;
        }
        const statusColor = { running: '#4ade80', stopped: '#fbbf24', derailed: '#ef4444', offline: '#6b7280' };
        for (const t of trains) {
            const [tx, ty] = proj(t.x, t.z);
            const color = statusColor[t.status] || '#9aa0aa';
            const rot = { N: 0, NE: 45, E: 90, SE: 135, S: 180, SW: 225, W: 270, NW: 315 }[t.heading] || 0;
            html += `<g class="map-train" data-train-id="${escapeHtml(t.trainId)}" transform="translate(${tx.toFixed(1)},${ty.toFixed(1)}) rotate(${rot})">`;
            html += `<polygon class="map-train-marker" points="0,-6 4,4 -4,4" fill="${color}"/>`;
            html += `<title>${escapeHtml(t.name)} · ${t.status}</title>`;
            html += `</g>`;
            html += `<text class="map-train-label" x="${(tx + 7).toFixed(1)}" y="${(ty - 4).toFixed(1)}">${escapeHtml(t.name)}</text>`;
        }
        svg.innerHTML = html;

        svg.querySelectorAll('.map-train').forEach(g => {
            g.addEventListener('click', () => selectTrain(g.dataset.trainId));
        });
    }

    // ---------- train list + detail ----------
    function renderTrainList() {
        const list = document.getElementById('train-list');
        const count = document.getElementById('train-count');
        count.textContent = state.trains.length + ' 列';
        if (state.trains.length === 0) {
            list.innerHTML = '<div class="empty-list">无列车</div>';
            return;
        }
        const statusColor = { running: '#4ade80', stopped: '#fbbf24', derailed: '#ef4444', offline: '#6b7280' };
        let html = '';
        for (const t of state.trains) {
            const sel = t.trainId === state.selectedTrainId ? ' selected' : '';
            html += `<div class="train-row${sel}" data-train-id="${escapeHtml(t.trainId)}">`;
            html += `<span class="train-dot" style="background:${statusColor[t.status] || '#9aa0aa'}"></span>`;
            html += `<span class="train-name">${escapeHtml(t.name || t.trainId)}</span>`;
            html += `<span class="train-speed">${fmtSpeed(t.speed)}</span>`;
            html += `</div>`;
        }
        list.innerHTML = html;
        list.querySelectorAll('.train-row').forEach(row => {
            row.addEventListener('click', () => selectTrain(row.dataset.trainId));
        });

        if (state.selectedTrainId) renderTrainDetail();
    }

    function selectTrain(id) {
        state.selectedTrainId = id;
        // Auto-open the detail section when a train is selected.
        const detailSection = document.getElementById('section-detail');
        if (detailSection.hasAttribute('hidden')) {
            detailSection.removeAttribute('hidden');
            detailSection.classList.add('open');
        }
        renderTrainList();
        renderMap();
        renderTrainDetail();
    }

    function renderTrainDetail() {
        const body = document.getElementById('train-detail-body');
        const section = document.getElementById('section-detail');
        const t = state.trains.find(x => x.trainId === state.selectedTrainId);
        if (!t) {
            section.setAttribute('hidden', '');
            section.classList.remove('open');
            return;
        }
        body.innerHTML = `
            <div class="detail-card">
                <div class="row"><span class="k">名称</span><span class="v">${escapeHtml(t.name)}</span></div>
                <div class="row"><span class="k">状态</span><span class="v">${escapeHtml(t.status)}</span></div>
                <div class="row"><span class="k">速度</span><span class="v">${fmtSpeed(t.speed)} b/t</span></div>
                <div class="row"><span class="k">方位</span><span class="v">${escapeHtml(t.heading || '—')}</span></div>
                <div class="row"><span class="k">车厢</span><span class="v">${t.carriageCount}</span></div>
                <div class="row"><span class="k">坐标</span><span class="v">${t.x}, ${t.y}, ${t.z}</span></div>
                <div class="row"><span class="k">维度</span><span class="v">${escapeHtml(t.dimension || '—')}</span></div>
                <div class="row"><span class="k">下一站</span><span class="v">${escapeHtml(t.navigationTarget || '—')}</span></div>
                <div class="row"><span class="k">导航中</span><span class="v">${t.navigating ? '是' : '否'}</span></div>
                <div class="row"><span class="k">更新</span><span class="v">${fmtTime(t.lastUpdatedMs)}</span></div>
            </div>`;
    }

    // ---------- route search (uses station tags when CRN present) ----------
    function populateRouteSelectors() {
        // Use station tag names when CRN is present; fall back to Create station names.
        let options;
        if (state.tags.length > 0) {
            options = state.tags.map(t => t.name).sort();
        } else {
            options = state.graph.stations.map(s => s.name).sort();
        }
        const fromOpts = '<option value="">起点…</option>' +
            options.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)}</option>`).join('');
        const toOpts = '<option value="">终点…</option>' +
            options.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)}</option>`).join('');
        document.getElementById('route-from').innerHTML = fromOpts;
        document.getElementById('route-to').innerHTML = toOpts;
    }

    document.getElementById('route-search').addEventListener('click', () => {
        const from = document.getElementById('route-from').value;
        const to = document.getElementById('route-to').value;
        const max = document.getElementById('route-max').value || 5;
        const results = document.getElementById('route-results');
        if (!from || !to) { results.innerHTML = '<p class="hint">请选择起点和终点</p>'; return; }
        results.innerHTML = '<p class="hint">搜索中…</p>';
        fetchJson(`/api/routes/search?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&maxResults=${max}`)
            .then(routes => {
                if (routes.length === 0) {
                    results.innerHTML = '<p class="hint">未找到路径</p>';
                    return;
                }
                results.innerHTML = routes.map(r => `
                    <div class="route-result">
                        <span class="hops">${r.hopCount} 跳</span>
                        · <span class="meta">距离 ${r.totalDistance.toFixed(1)} · 预计 ${(r.estimatedTimeMs / 1000).toFixed(0)}s</span>
                        <div class="meta" style="margin-top:4px;">${r.hops.map(escapeHtml).join(' → ')}</div>
                    </div>`).join('');
            }).catch(err => { results.innerHTML = '<p class="hint">搜索失败: ' + escapeHtml(err.message) + '</p>'; });
    });

    // ---------- departures (auto-refresh when section open) ----------
    function populateDepartureSelector() {
        const stations = state.graph.stations.map(s => s.name).sort();
        document.getElementById('dep-station').innerHTML =
            '<option value="">全部站点</option>' +
            stations.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)}</option>`).join('');
    }

    function refreshDepartures() {
        const station = document.getElementById('dep-station').value;
        const limit = document.getElementById('dep-limit').value || 30;
        const list = document.getElementById('dep-list');
        const url = station
            ? `/api/departures?station=${encodeURIComponent(station)}&limit=${limit}`
            : `/api/departures/all?limit=${limit}`;
        fetchJson(url).then(records => {
            if (records.length === 0) { list.innerHTML = '<p class="hint">暂无到发记录</p>'; return; }
            list.innerHTML = records.map(r => `
                <div class="dep-row">
                    <span class="ts">${fmtTime(r.ts)}</span>
                    <span class="train">${escapeHtml(r.trainName)} → ${escapeHtml(r.stationName)}</span>
                    <span class="dir dir-${r.direction}">${r.direction === 'arrival' ? '到' : '发'}</span>
                </div>`).join('');
        }).catch(err => { list.innerHTML = '<p class="hint">加载失败: ' + escapeHtml(err.message) + '</p>'; });
    }

    document.getElementById('dep-refresh').addEventListener('click', refreshDepartures);

    // ---------- metadata (categories / lines / tags — read-only, from CRN) ----------
    function loadMetadata() {
        Promise.all([
            fetchJson('/api/train-categories'),
            fetchJson('/api/train-lines'),
            fetchJson('/api/station-tags')
        ]).then(([cats, lines, tags]) => {
            state.categories = cats;
            state.lines = lines;
            state.tags = tags;
            tagsLoaded = true;
            renderMetadata();
            // If route section is already open, refresh its selectors with tag names.
            if (document.getElementById('section-routes').classList.contains('open')) {
                populateRouteSelectors();
            }
        }).catch(err => console.warn('metadata load failed:', err));
    }

    function renderMetadata() {
        const catTbody = document.getElementById('cat-tbody');
        const lineTbody = document.getElementById('line-tbody');
        const tagTbody = document.getElementById('tag-tbody');

        catTbody.innerHTML = state.categories.length === 0
            ? '<tr><td class="empty-list">暂无</td></tr>'
            : state.categories.map(c => `<tr><td><span class="swatch" style="background:${intToHex(c.color)}"></span>${escapeHtml(c.name)}</td></tr>`).join('');

        lineTbody.innerHTML = state.lines.length === 0
            ? '<tr><td class="empty-list">暂无</td></tr>'
            : state.lines.map(l => `<tr><td><span class="swatch" style="background:${intToHex(l.color)}"></span>${escapeHtml(l.name)}</td></tr>`).join('');

        tagTbody.innerHTML = state.tags.length === 0
            ? '<tr><td class="empty-list">暂无</td></tr>'
            : state.tags.map(t => `<tr><td>${escapeHtml(t.name)}${t.stationNames && t.stationNames.length > 0 ? '<span class="hint" style="margin-left:6px;">(' + t.stationNames.length + ' 站)</span>' : ''}</td></tr>`).join('');
    }

    // ---------- CRN health ----------
    function pollHealth() {
        fetchJson('/api/trains/health').then(h => {
            const dot = document.getElementById('crn-dot');
            const txt = document.getElementById('crn-status');
            if (h.crn === 'detected') {
                dot.className = 'trains-status-dot dot-ok';
                txt.textContent = '已联动';
                // Lazy-load metadata + tags when CRN is first detected.
                if (!tagsLoaded) loadMetadata();
            } else {
                dot.className = 'trains-status-dot dot-warn';
                txt.textContent = '未安装';
            }
            document.getElementById('stat-lines').textContent = h.crnLines || 0;
        }).catch(() => {
            document.getElementById('crn-dot').className = 'trains-status-dot dot-err';
            document.getElementById('crn-status').textContent = '离线';
        });
    }

    // ---------- bootstrap ----------
    pollLive();
    pollHealth();
    setInterval(pollLive, POLL_MS);
    setInterval(pollHealth, POLL_MS * 10);

    // Re-render the map on window resize.
    let resizeTimer = null;
    window.addEventListener('resize', () => {
        if (resizeTimer) clearTimeout(resizeTimer);
        resizeTimer = setTimeout(renderMap, 150);
    });
})();
