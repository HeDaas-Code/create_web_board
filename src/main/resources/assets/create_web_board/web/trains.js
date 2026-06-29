/**
 * trains.js — front-end for the Create train dispatch dashboard.
 *
 * Polls the REST API every 2s for live train + topology data and renders an SVG map
 * (auto-fitting to the bounds of all track nodes). Management tabs (categories / lines /
 * tags / routes / departures) fetch on demand when their tab is opened.
 *
 * No external dependencies — the map is hand-drawn SVG so the jar stays slim (no Leaflet
 * bundle to ship). Coordinate system: world (x, z) → SVG (x, y) with z mapped to y and
 * a uniform scale + pan fit.
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
        activePane: 'map',
        mapBounds: null,  // {minX, maxX, minZ, maxZ} cached from last render
    };

    const POLL_MS = 2000;

    // ---------- helpers ----------
    function fetchJson(url) {
        return fetch(url).then(r => {
            if (!r.ok) throw new Error(url + ' → ' + r.status);
            return r.json();
        });
    }

    function postJson(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        }).then(r => r.ok ? r.json() : Promise.reject(new Error(url + ' → ' + r.status)));
    }

    function putJson(url, body) {
        return fetch(url, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        }).then(r => r.ok ? r.json() : Promise.reject(new Error(url + ' → ' + r.status)));
    }

    function del(url) {
        return fetch(url, { method: 'DELETE' })
            .then(r => { if (!r.ok) throw new Error(url + ' → ' + r.status); });
    }

    /** Convert an int RGB color (e.g. 0x3b82f6 or -1 for "default") to a CSS hex string. */
    function intToHex(c) {
        if (c == null || c < 0) return '#9aa0aa';  // default grey
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

    // ---------- tab switching ----------
    document.querySelectorAll('.trains-tab').forEach(btn => {
        btn.addEventListener('click', () => {
            const pane = btn.dataset.pane;
            document.querySelectorAll('.trains-tab').forEach(b => b.classList.toggle('active', b === btn));
            document.querySelectorAll('.pane').forEach(p => p.classList.toggle('active', p.id === 'pane-' + pane));
            state.activePane = pane;
            if (pane === 'categories') loadCategories();
            else if (pane === 'lines') loadLines();
            else if (pane === 'tags') loadTags();
            else if (pane === 'routes') populateRouteSelectors();
            else if (pane === 'departures') populateDepartureSelector();
        });
    });

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
        // stat-lines is set by pollHealth() from the CRN line count, not from graph edges.
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

        // Compute bounds from nodes + train positions (train-only case needs bounds too).
        let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
        const allPoints = nodes.map(n => [n.x, n.z]).concat(trains.map(t => [t.x, t.z]));
        for (const [x, z] of allPoints) {
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
        if (!isFinite(minX)) { minX = maxX = minZ = maxZ = 0; }
        // Pad bounds by 5% so markers aren't clipped at the edge.
        const padX = Math.max(8, (maxX - minX) * 0.05);
        const padZ = Math.max(8, (maxZ - minZ) * 0.05);
        minX -= padX; maxX += padX; minZ -= padZ; maxZ += padZ;
        const spanX = Math.max(1, maxX - minX);
        const spanZ = Math.max(1, maxZ - minZ);
        // Uniform scale: fit the larger axis, center the other.
        const scale = Math.min(w / spanX, h / spanZ);
        const offX = (w - spanX * scale) / 2;
        const offZ = (h - spanZ * scale) / 2;
        // Project (worldX, worldZ) → (svgX, svgY). Z is inverted so +Z (south) is downward.
        function proj(x, z) {
            return [offX + (x - minX) * scale, offZ + (z - minZ) * scale];
        }

        let html = '';
        // Edges
        for (const e of edges) {
            const [x1, y1] = proj(e.a.x, e.a.z);
            const [x2, y2] = proj(e.b.x, e.b.z);
            html += `<line class="map-edge" x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}"/>`;
        }
        // Stations
        for (const s of stations) {
            const [sx, sy] = proj(s.x, s.z);
            html += `<circle class="map-station" cx="${sx.toFixed(1)}" cy="${sy.toFixed(1)}" r="4"><title>${escapeHtml(s.name)}</title></circle>`;
            html += `<text class="map-station-label" x="${(sx + 6).toFixed(1)}" y="${(sy + 3).toFixed(1)}">${escapeHtml(s.name)}</text>`;
        }
        // Trains — colored by status, oriented by heading.
        const statusColor = { running: '#4ade80', stopped: '#fbbf24', derailed: '#ef4444', offline: '#6b7280' };
        for (const t of trains) {
            const [tx, ty] = proj(t.x, t.z);
            const color = statusColor[t.status] || '#9aa0aa';
            // Heading arrow: rotate a small triangle. We don't know the exact angle, just
            // the 8-direction label, so map each label to a rough rotation (degrees, 0=up).
            const rot = { N: 0, NE: 45, E: 90, SE: 135, S: 180, SW: 225, W: 270, NW: 315 }[t.heading] || 0;
            const sel = t.trainId === state.selectedTrainId ? ' selected' : '';
            html += `<g class="map-train" data-train-id="${escapeHtml(t.trainId)}" transform="translate(${tx.toFixed(1)},${ty.toFixed(1)}) rotate(${rot})">`;
            html += `<polygon class="map-train-marker" points="0,-6 4,4 -4,4" fill="${color}"/>`;
            html += `<title>${escapeHtml(t.name)} · ${t.status}</title>`;
            html += `</g>`;
            html += `<text class="map-train-label" x="${(tx + 7).toFixed(1)}" y="${(ty - 4).toFixed(1)}">${escapeHtml(t.name)}</text>`;
        }
        svg.innerHTML = html;

        // Wire train marker clicks
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
        renderTrainList();
        renderMap();
        renderTrainDetail();
    }

    function renderTrainDetail() {
        const panel = document.getElementById('train-detail');
        const body = document.getElementById('train-detail-body');
        const t = state.trains.find(x => x.trainId === state.selectedTrainId);
        if (!t) { panel.hidden = true; return; }
        panel.hidden = false;
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

    // ---------- categories (read-only, synced from CRN) ----------
    function loadCategories() {
        return fetchJson('/api/train-categories').then(list => {
            state.categories = list;
            renderCategoryTable();
            return list;
        }).catch(err => console.warn('categories load failed:', err));
    }

    function renderCategoryTable() {
        const tbody = document.getElementById('cat-tbody');
        if (state.categories.length === 0) {
            tbody.innerHTML = '<tr><td colspan="2" class="empty-list">暂无分类（请在游戏内通过 CRN 添加）</td></tr>';
            return;
        }
        tbody.innerHTML = state.categories.map(c => `
            <tr>
                <td>${escapeHtml(c.name)}</td>
                <td><span class="swatch" style="background:${intToHex(c.color)}"></span>${intToHex(c.color)}</td>
            </tr>`).join('');
    }

    // ---------- lines (read-only, synced from CRN) ----------
    function loadLines() {
        return fetchJson('/api/train-lines').then(list => {
            state.lines = list;
            renderLineTable();
            return list;
        }).catch(err => console.warn('lines load failed:', err));
    }

    function renderLineTable() {
        const tbody = document.getElementById('line-tbody');
        if (state.lines.length === 0) {
            tbody.innerHTML = '<tr><td colspan="2" class="empty-list">暂无线路（请在游戏内通过 CRN 添加）</td></tr>';
            return;
        }
        tbody.innerHTML = state.lines.map(l => `
            <tr>
                <td><span class="swatch" style="background:${intToHex(l.color)}"></span>${escapeHtml(l.name)}</td>
                <td>${intToHex(l.color)}</td>
            </tr>`).join('');
    }

    // ---------- tags (read-only, synced from CRN) ----------
    function loadTags() {
        return fetchJson('/api/station-tags').then(list => {
            state.tags = list;
            renderTagTable();
        }).catch(err => console.warn('tags load failed:', err));
    }

    function renderTagTable() {
        const tbody = document.getElementById('tag-tbody');
        if (state.tags.length === 0) {
            tbody.innerHTML = '<tr><td class="empty-list">暂无标签（请在游戏内通过 CRN 添加）</td></tr>';
            return;
        }
        tbody.innerHTML = state.tags.map(t => `
            <tr>
                <td>${escapeHtml(t.name)}</td>
            </tr>`).join('');
    }

    // ---------- route search ----------
    function populateRouteSelectors() {
        const stations = state.graph.stations.map(s => s.name).sort();
        const opts = '<option value="">起点…</option>' +
            stations.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)}</option>`).join('');
        document.getElementById('route-from').innerHTML = opts;
        document.getElementById('route-to').innerHTML =
            opts.replace('起点…', '终点…');
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

    // ---------- departures ----------
    function populateDepartureSelector() {
        const stations = state.graph.stations.map(s => s.name).sort();
        document.getElementById('dep-station').innerHTML =
            '<option value="">全部站点</option>' +
            stations.map(n => `<option value="${escapeHtml(n)}">${escapeHtml(n)}</option>`).join('');
        refreshDepartures();
    }

    function refreshDepartures() {
        const station = document.getElementById('dep-station').value;
        const limit = document.getElementById('dep-limit').value || 50;
        const list = document.getElementById('dep-list');
        list.innerHTML = '<p class="hint">加载中…</p>';
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

    // ---------- CRN health ----------
    function pollHealth() {
        fetchJson('/api/trains/health').then(h => {
            const dot = document.getElementById('crn-dot');
            const txt = document.getElementById('crn-status');
            if (h.crn === 'detected') { dot.className = 'trains-status-dot dot-ok'; txt.textContent = '已联动'; }
            else { dot.className = 'trains-status-dot dot-warn'; txt.textContent = '未安装'; }
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

    // Re-render the map on window resize (SVG is percentage-sized, contents are not).
    let resizeTimer = null;
    window.addEventListener('resize', () => {
        if (resizeTimer) clearTimeout(resizeTimer);
        resizeTimer = setTimeout(renderMap, 150);
    });
})();
