// Create Web Board — kanban dashboard client
//
// Renders every mirrored Display Link as its own card in a responsive grid.
// No sidebar / no selection — each board is always visible, like a Feishu board.
//
// Wire-up:
//   - WebSocket at /ws for live board:update / board:remove events
//   - REST fallback: /api/boards on connect (and every 5s as safety net)
//   - /api/health polled for ws connection count shown in the topbar
//   - Reconnect with exponential backoff (cap 10s)
//
// Element map (see index.html):
//   #status-dot, #status-text  — WS status pill
//   #board-count, #ws-count    — topbar stats
//   #clock                      — local clock
//   #search                     — filter input (matches name or sourceType)
//   #board-grid                 — <main>, card container
//   #empty-state                — shown when no boards (or all filtered out)

(function () {
    "use strict";

    const $ = (id) => document.getElementById(id);
    const els = {
        statusDot: $("status-dot"),
        statusText: $("status-text"),
        boardCount: $("board-count"),
        wsCount: $("ws-count"),
        clock: $("clock"),
        search: $("search"),
        boardGrid: $("board-grid"),
        emptyState: $("empty-state"),
        // Modal
        modal: $("modal"),
        modalTitle: $("modal-title-h"),
        modalClose: $("modal-close"),
        nameInput: $("modal-name-input"),
        saveName: $("modal-save-name"),
        keyHint: $("modal-key-hint"),
        currentLines: $("modal-current-lines"),
        historyBox: $("modal-history"),
        historyCount: $("modal-history-count"),
        footMeta: $("modal-foot-meta"),
        deleteBtn: $("modal-delete"),
    };

    const state = {
        boards: new Map(),    // name -> BoardContent
        ws: null,
        backoff: 500,         // ms, doubles on failure, cap 10s
        filter: "",           // current filter query (lower-case)
        lastSeenInterval: null,
        modalName: null,      // stable key of the board currently shown in the modal (null = closed)
    };

    // ---------- WebSocket ----------

    function connect() {
        const proto = location.protocol === "https:" ? "wss:" : "ws:";
        const url = proto + "//" + location.host + "/ws";
        const ws = new WebSocket(url);
        state.ws = ws;

        ws.onopen = () => {
            setStatus("ok", "已连接");
            state.backoff = 500;
        };

        ws.onmessage = (ev) => {
            let msg;
            try { msg = JSON.parse(ev.data); } catch (_) { return; }
            handleMessage(msg);
        };

        ws.onclose = () => {
            setStatus("off", "已断开");
            state.ws = null;
            scheduleReconnect();
        };

        ws.onerror = () => {
            setStatus("err", "连接错误");
        };
    }

    function scheduleReconnect() {
        const delay = Math.min(state.backoff, 10000);
        state.backoff = Math.min(state.backoff * 2, 10000);
        setTimeout(connect, delay);
    }

    function handleMessage(msg) {
        switch (msg.type) {
            case "snapshot":
                state.boards.clear();
                (msg.boards || []).forEach((b) => {
                    b._seenAt = Date.now();
                    state.boards.set(b.name, b);
                });
                render();
                refreshModalIfOpen();
                break;
            case "update":
                msg.board._seenAt = Date.now();
                state.boards.set(msg.board.name, msg.board);
                render();
                refreshModalIfOpen();
                break;
            case "remove":
                state.boards.delete(msg.name);
                if (state.modalName === msg.name) closeModal();
                render();
                break;
            default:
                // unknown — ignore
        }
    }

    /** If the modal is open and its board just got an update, re-render its live fields. */
    function refreshModalIfOpen() {
        if (!state.modalName) return;
        const b = state.boards.get(state.modalName);
        if (b) {
            // Preserve any text the user is currently typing in the name field.
            const focused = document.activeElement === els.nameInput;
            const draft = els.nameInput.value;
            renderModal(b);
            if (focused) {
                els.nameInput.focus();
                els.nameInput.value = draft;
            }
        }
    }

    // ---------- REST fallback ----------

    async function fetchAll() {
        try {
            const r = await fetch("/api/boards");
            if (!r.ok) return;
            const list = await r.json();
            const next = new Map();
            let staleChanged = false;
            list.forEach((b) => {
                // Preserve _seenAt from existing state — do NOT reset to Date.now() here.
                // fetchAll is a safety-net poll; resetting _seenAt would prevent the
                // client-side stale timer from ever firing (bug: destroyed DL never went offline).
                // Only WS update/snapshot events set _seenAt (those are real data pushes).
                const prev = state.boards.get(b.name);
                b._seenAt = prev ? prev._seenAt : Date.now();
                // Detect stale-status transitions to trigger a re-render.
                const prevStale = prev ? !!prev.stale : false;
                if (!!b.stale !== prevStale) staleChanged = true;
                next.set(b.name, b);
            });
            // Re-render if keys changed OR any board's stale status flipped.
            if (!sameKeys(state.boards, next) || staleChanged) {
                state.boards = next;
                render();
            } else {
                state.boards = next;
            }
            // Keep an open modal in sync even when WS is down (REST is the only source then).
            refreshModalIfOpen();
        } catch (_) { /* offline; WS will heal */ }
    }

    async function fetchHealth() {
        try {
            const r = await fetch("/api/health");
            if (!r.ok) return;
            const h = await r.json();
            els.wsCount.textContent = String(h.wsConnections || 0);
        } catch (_) { /* ignore */ }
    }

    // ---------- Rendering ----------

    function setStatus(kind, text) {
        els.statusDot.className = "dot dot-" + (kind === "ok" ? "ok" : kind === "err" ? "err" : "off");
        els.statusText.textContent = text;
    }

    function matchesFilter(b) {
        if (!state.filter) return true;
        if (b.name && b.name.toLowerCase().includes(state.filter)) return true;
        if (b.effectiveName && b.effectiveName.toLowerCase().includes(state.filter)) return true;
        if (b.sourceType && b.sourceType.toLowerCase().includes(state.filter)) return true;
        return false;
    }

    function render() {
        const all = Array.from(state.boards.values()).slice().sort((a, b) => a.name.localeCompare(b.name));
        const visible = all.filter(matchesFilter);

        els.boardCount.textContent = String(all.length);

        // Empty state: show the big friendly placeholder whenever there's nothing to show.
        if (visible.length === 0) {
            els.boardGrid.innerHTML = "";
            els.emptyState.hidden = false;
            return;
        }
        els.emptyState.hidden = true;

        // Rebuild the grid. For typical dashboards (tens of boards) a full rebuild is cheap
        // and avoids the bookkeeping of diffing. If this ever scales to hundreds, switch to
        // keyed updates (re-use existing .card nodes by data-name).
        const frag = document.createDocumentFragment();
        visible.forEach((b) => frag.appendChild(renderCard(b)));
        els.boardGrid.innerHTML = "";
        els.boardGrid.appendChild(frag);
    }

    function renderCard(b) {
        const card = document.createElement("article");
        card.className = "card";
        card.dataset.name = b.name;
        card.tabIndex = 0;
        card.setAttribute("role", "button");
        card.setAttribute("aria-label", "打开看板详情: " + displayTitle(b));

        // Stale detection: prefer server's stale field (computed from lastUpdatedMs on the
        // backend). Fall back to client-side _seenAt check for WS-only updates where the
        // server stale field might lag by a poll cycle.
        const stale = b.stale || (!b._seenAt || Date.now() - b._seenAt > 30000);
        if (stale) card.classList.add("card-offline");

        // Open the detail modal on click. Management (rename/history/delete) lives there so it's
        // always reachable regardless of stale state — fixes "没有删除按钮" (the old inline button
        // only rendered when stale).
        card.addEventListener("click", () => openModal(b.name));
        card.addEventListener("keydown", (ev) => {
            if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); openModal(b.name); }
        });

        // Header: title + live badge
        const head = document.createElement("header");
        head.className = "card-head";

        const title = document.createElement("h3");
        title.className = "card-title";
        title.textContent = displayTitle(b);
        title.title = displayTitle(b);

        const live = document.createElement("span");
        live.className = "card-live";
        if (stale) {
            live.innerHTML = '<span class="live-dot"></span>离线';
        } else {
            live.innerHTML = '<span class="live-dot"></span>live';
        }

        head.appendChild(title);
        head.appendChild(live);

        // Meta: source-type chip + update time
        const meta = document.createElement("div");
        meta.className = "card-meta";

        if (b.sourceType) {
            const chip = document.createElement("span");
            chip.className = "chip";
            chip.textContent = b.sourceLabel || shortSource(b.sourceType);
            chip.title = b.sourceType;
            meta.appendChild(chip);
        }

        const time = document.createElement("span");
        time.className = "card-time";
        time.textContent = formatTime(b.lastUpdatedMs);
        time.title = "最后更新";
        meta.appendChild(time);

        // Last-seen (relative time)
        const lastSeen = document.createElement("span");
        lastSeen.className = "last-seen";
        if (b._seenAt) {
            const elapsed = Date.now() - b._seenAt;
            lastSeen.textContent = "上次更新: " + formatRelative(elapsed);
        }
        lastSeen.dataset.boardName = b.name;
        meta.appendChild(lastSeen);

        // Lines
        const lines = document.createElement("ol");
        lines.className = "card-lines";
        (b.lines || []).forEach((line) => {
            const li = document.createElement("li");
            li.textContent = line;
            lines.appendChild(li);
        });

        // Footer
        const foot = document.createElement("footer");
        foot.className = "card-foot";
        const count = (b.lines || []).length;
        foot.textContent = count + (count === 1 ? " 行" : " 行");

        card.appendChild(head);
        card.appendChild(meta);
        card.appendChild(lines);
        card.appendChild(foot);
        return card;
    }

    // ---------- Modal ----------

    /** The name to display for a board: user-set displayName if present, else the stable key. */
    function displayTitle(b) {
        return (b && b.effectiveName) ? b.effectiveName : (b ? b.name : "");
    }

    function openModal(name) {
        const b = state.boards.get(name);
        if (!b) return;
        state.modalName = name;
        renderModal(b);
        els.modal.hidden = false;
        // Focus the name input so the user can rename immediately.
        setTimeout(() => els.nameInput.focus(), 0);
        loadHistory(name);
    }

    function closeModal() {
        state.modalName = null;
        els.modal.hidden = true;
    }

    /** Re-render the modal's static fields from a board snapshot. Called on open + WS updates. */
    function renderModal(b) {
        els.modalTitle.textContent = displayTitle(b);
        els.nameInput.value = b.displayName || "";
        els.keyHint.textContent = "位置标识: " + b.name + "  ·  " + (b.sourceType || "unknown");
        els.footMeta.textContent = (b.stale ? "离线" : "在线") + "  ·  更新于 " + formatTime(b.lastUpdatedMs);

        // Current lines
        els.currentLines.innerHTML = "";
        (b.lines || []).forEach((line) => {
            const li = document.createElement("li");
            li.textContent = line;
            els.currentLines.appendChild(li);
        });

        // Disable delete while a live DL is actively refreshing? No — allow always; if the DL is
        // still active the board just reappears on the next refresh. We note that in the tooltip.
        els.deleteBtn.title = b.stale
            ? "从看板移除（看板已离线，删除后不会自动恢复）"
            : "从看板移除（看板仍在线，DL 下次刷新会重建；如需永久移除请在游戏内关闭 Web 开关）";
    }

    async function loadHistory(name) {
        els.historyBox.innerHTML = '<div class="hist-empty">加载中…</div>';
        els.historyCount.textContent = "";
        try {
            const r = await fetch("/api/boards/" + encodeURIComponent(name) + "/history");
            if (!r.ok) {
                els.historyBox.innerHTML = '<div class="hist-empty">（暂无历史）</div>';
                return;
            }
            const list = await r.json();
            renderHistory(list);
        } catch (_) {
            els.historyBox.innerHTML = '<div class="hist-empty">加载失败</div>';
        }
    }

    /**
     * Render the board's history. Tries to visualize it as an SVG line chart:
     * for each line index, extract the first parseable number from every snapshot
     * and draw it as a trend line over time. Falls back to a collapsible raw
     * snapshot list when no numeric series can be built (e.g. pure-text boards).
     *
     * Zero dependencies — the chart is hand-drawn SVG so it works offline on
     * the localhost dashboard (no CDN, keeps the mod jar slim).
     */
    function renderHistory(list) {
        els.historyBox.innerHTML = "";
        if (!list || list.length === 0) {
            els.historyBox.innerHTML = '<div class="hist-empty">（暂无历史快照）</div>';
            els.historyCount.textContent = "";
            return;
        }
        els.historyCount.textContent = "共 " + list.length + " 条";

        const frag = document.createDocumentFragment();
        const series = buildNumericSeries(list);

        if (series.length > 0) {
            frag.appendChild(renderChart(series));
        } else {
            const noChart = document.createElement("div");
            noChart.className = "hist-empty";
            noChart.textContent = "（历史内容无可提取数值，下方为原始快照）";
            frag.appendChild(noChart);
        }

        // Always keep the raw snapshots as a collapsible fallback / detail view.
        frag.appendChild(renderRawSnapshots(list));
        els.historyBox.appendChild(frag);
    }

    /** First number (int or decimal, optional sign) found in a line, or null. */
    const NUM_RE = /-?\d+(?:\.\d+)?/;

    /**
     * Build numeric series from history. For each line index i, collect
     * {ts, value} across all snapshots by matching the first number in lines[i].
     * A line becomes a series only if >= 2 snapshots yield a number for it.
     * Missing values are forward-filled (and leading nulls trimmed) so lines
     * stay continuous. Label is the line's text in the latest snapshot.
     */
    function buildNumericSeries(list) {
        let maxLines = 0;
        list.forEach((he) => { if (he.lines) maxLines = Math.max(maxLines, he.lines.length); });
        const series = [];
        for (let i = 0; i < maxLines; i++) {
            const points = [];
            let valid = 0;
            let lastLabel = "";
            list.forEach((he) => {
                const line = he.lines && he.lines[i];
                if (line == null) { points.push({ ts: he.ts, value: null }); return; }
                lastLabel = line;
                const m = line.match(NUM_RE);
                if (m) { points.push({ ts: he.ts, value: parseFloat(m[0]) }); valid++; }
                else points.push({ ts: he.ts, value: null });
            });
            if (valid < 2) continue;
            // forward-fill, then trim leading nulls (can't back-fill what we never saw)
            let lastVal = null;
            for (let k = 0; k < points.length; k++) {
                if (points[k].value != null) lastVal = points[k].value;
                else if (lastVal != null) points[k].value = lastVal;
            }
            let start = 0;
            while (start < points.length && points[start].value == null) start++;
            const trimmed = points.slice(start);
            if (trimmed.length < 2) continue;
            series.push({ label: truncate(lastLabel, 28), points: trimmed });
        }
        return series;
    }

    function truncate(s, n) {
        if (!s) return "";
        return s.length > n ? s.slice(0, n - 1) + "…" : s;
    }

    /** Escape a string for safe insertion into SVG/HTML text content. */
    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, (c) =>
            ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
    }

    function chartColor(i) {
        // Palette tuned for the dark theme — distinct, colorblind-friendlier hues.
        const palette = ["#FAA21B", "#4ec9b0", "#5aa9ff", "#f48771", "#c084fc", "#facc15"];
        return palette[i % palette.length];
    }

    function formatNum(v) {
        if (Math.abs(v) >= 10000) return (v / 1000).toFixed(1) + "k";
        if (Number.isInteger(v)) return String(v);
        return v.toFixed(2);
    }

    /** Build the legend + SVG line chart for the given series. */
    function renderChart(series) {
        const wrap = document.createElement("div");
        wrap.className = "hist-chart-wrap";

        const legend = document.createElement("div");
        legend.className = "chart-legend";
        series.forEach((s, idx) => {
            const item = document.createElement("span");
            item.className = "legend-item";
            item.title = s.label;
            const sw = document.createElement("span");
            sw.className = "legend-swatch";
            sw.style.background = chartColor(idx);
            const label = document.createElement("span");
            label.className = "legend-label";
            label.textContent = s.label;
            item.appendChild(sw);
            item.appendChild(label);
            legend.appendChild(item);
        });
        wrap.appendChild(legend);

        wrap.appendChild(renderChartSvg(series));
        return wrap;
    }

    /** Hand-drawn SVG line chart. viewBox is fixed; CSS scales it to container width. */
    function renderChartSvg(series) {
        const W = 800, H = 260;
        const padL = 52, padR = 14, padT = 14, padB = 32;
        const plotW = W - padL - padR;
        const plotH = H - padT - padB;

        let tsMin = Infinity, tsMax = -Infinity, vMin = Infinity, vMax = -Infinity;
        series.forEach((s) => s.points.forEach((p) => {
            if (p.ts < tsMin) tsMin = p.ts;
            if (p.ts > tsMax) tsMax = p.ts;
            if (p.value < vMin) vMin = p.value;
            if (p.value > vMax) vMax = p.value;
        }));
        if (tsMin === tsMax) tsMax = tsMin + 1; // single-point guard
        if (vMin === vMax) vMax = vMin + 1;
        const vRange = vMax - vMin;
        vMin -= vRange * 0.1; vMax += vRange * 0.1; // 10% headroom

        const xOf = (ts) => padL + (ts - tsMin) / (tsMax - tsMin) * plotW;
        const yOf = (v) => padT + plotH - (v - vMin) / (vMax - vMin) * plotH;

        const parts = [];
        parts.push('<svg class="hist-chart" viewBox="0 0 ' + W + " " + H +
            '" preserveAspectRatio="xMidYMid meet" role="img" aria-label="历史数值折线图">');

        // Y gridlines + labels (min / mid / max)
        [vMin, vMin + (vMax - vMin) / 2, vMax].forEach((v) => {
            const y = yOf(v);
            parts.push('<line class="chart-grid" x1="' + padL + '" y1="' + y +
                '" x2="' + (W - padR) + '" y2="' + y + '"/>');
            parts.push('<text class="chart-axis-label" x="' + (padL - 6) + '" y="' + (y + 3) +
                '" text-anchor="end">' + formatNum(v) + "</text>");
        });
        // X labels (first / mid / last timestamp)
        [tsMin, tsMin + (tsMax - tsMin) / 2, tsMax].forEach((ts) => {
            const x = xOf(ts);
            parts.push('<text class="chart-axis-label" x="' + x + '" y="' + (H - padB + 16) +
                '" text-anchor="middle">' + formatTime(ts) + "</text>");
        });
        // Axes
        parts.push('<line class="chart-axis" x1="' + padL + '" y1="' + padT +
            '" x2="' + padL + '" y2="' + (H - padB) + '"/>');
        parts.push('<line class="chart-axis" x1="' + padL + '" y1="' + (H - padB) +
            '" x2="' + (W - padR) + '" y2="' + (H - padB) + '"/>');

        // Series polylines + points (with native <title> tooltips on hover)
        series.forEach((s, idx) => {
            const color = chartColor(idx);
            const pts = s.points.map((p) => xOf(p.ts) + "," + yOf(p.value)).join(" ");
            parts.push('<polyline class="chart-line" points="' + pts +
                '" fill="none" stroke="' + color + '"/>');
            s.points.forEach((p) => {
                parts.push('<circle class="chart-point" cx="' + xOf(p.ts) + '" cy="' + yOf(p.value) +
                    '" r="3" fill="' + color + '"><title>' +
                    escapeHtml(s.label) + " · " + formatTime(p.ts) + " · " + formatNum(p.value) +
                    "</title></circle>");
            });
        });

        parts.push("</svg>");
        const div = document.createElement("div");
        div.innerHTML = parts.join("");
        return div.firstElementChild;
    }

    /** Collapsible raw snapshot list (newest-first), kept as a fallback detail view. */
    function renderRawSnapshots(list) {
        const det = document.createElement("details");
        det.className = "hist-raw";
        const sum = document.createElement("summary");
        sum.textContent = "原始快照（" + list.length + "）";
        det.appendChild(sum);

        const box = document.createElement("div");
        box.className = "hist-raw-list";
        for (let i = list.length - 1; i >= 0; i--) {
            const he = list[i];
            const item = document.createElement("div");
            item.className = "hist-item";

            const head = document.createElement("div");
            head.className = "hist-item-head";
            const idx = document.createElement("span");
            idx.className = "hist-idx";
            idx.textContent = "#" + (i + 1);
            const ts = document.createElement("span");
            ts.textContent = formatTime(he.ts) + "  ·  " + formatRelative(Date.now() - he.ts);
            head.appendChild(idx);
            head.appendChild(ts);

            const ol = document.createElement("ol");
            ol.className = "hist-lines";
            (he.lines || []).forEach((line) => {
                const li = document.createElement("li");
                li.textContent = line;
                ol.appendChild(li);
            });
            if ((he.lines || []).length === 0) {
                const li = document.createElement("li");
                li.textContent = "（空）";
                li.style.color = "var(--text-3)";
                ol.appendChild(li);
            }

            item.appendChild(head);
            item.appendChild(ol);
            box.appendChild(item);
        }
        det.appendChild(box);
        return det;
    }

    async function saveDisplayName() {
        const name = state.modalName;
        if (!name) return;
        const value = els.nameInput.value.trim();
        els.saveName.disabled = true;
        try {
            const body = JSON.stringify({ displayName: value });
            const r = await fetch("/api/boards/" + encodeURIComponent(name), {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: body,
            });
            if (r.ok) {
                // The WS update (fired by the server's rename) will refresh the card + modal.
                // Also update local state immediately for snappy feedback.
                const b = state.boards.get(name);
                if (b) {
                    b.displayName = value || null;
                    b.effectiveName = value || b.name;
                    render();
                    renderModal(b);
                }
            }
        } catch (_) { /* ignore */ } finally {
            els.saveName.disabled = false;
        }
    }

    async function deleteBoard() {
        const name = state.modalName;
        if (!name) return;
        const b = state.boards.get(name);
        const msg = b && b.stale
            ? "确认从看板移除「" + displayTitle(b) + "」？该看板已离线。"
            : "确认从看板移除「" + displayTitle(b) + "」？\n注意：看板仍在线，Display Link 下次刷新会重建它。如需永久移除，请在游戏内关闭该 DL 的 Web 开关。";
        if (!window.confirm(msg)) return;
        els.deleteBtn.disabled = true;
        try {
            const r = await fetch("/api/boards/" + encodeURIComponent(name), { method: "DELETE" });
            if (r.ok) {
                state.boards.delete(name);
                closeModal();
                render();
            }
        } catch (_) { /* ignore */ } finally {
            els.deleteBtn.disabled = false;
        }
    }

    // ---------- Helpers ----------

    /** "create:nixie_tube" → "nixie_tube"; "create_web_board:foo" → "foo". */
    function shortSource(sourceType) {
        if (!sourceType) return "";
        const i = sourceType.indexOf(":");
        return i >= 0 ? sourceType.slice(i + 1) : sourceType;
    }

    function formatTime(ms) {
        if (!ms) return "—";
        const d = new Date(ms);
        const pad = (n) => String(n).padStart(2, "0");
        return pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    }

    /** Format milliseconds as a human-friendly relative time string. */
    function formatRelative(ms) {
        if (ms < 60000) return Math.floor(ms / 1000) + "s 前";
        if (ms < 3600000) return Math.floor(ms / 60000) + "m 前";
        return Math.floor(ms / 3600000) + "h 前";
    }

    function sameKeys(a, b) {
        if (a.size !== b.size) return false;
        for (const k of a.keys()) if (!b.has(k)) return false;
        return true;
    }

    function tickClock() {
        const d = new Date();
        const pad = (n) => String(n).padStart(2, "0");
        els.clock.textContent = pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    }

    // ---------- Last-seen ticker ----------

    function startLastSeenTicker() {
        state.lastSeenInterval = setInterval(() => {
            let needRender = false;
            // Update last-seen text on all visible cards
            document.querySelectorAll(".last-seen").forEach((el) => {
                const name = el.dataset.boardName;
                const b = state.boards.get(name);
                if (!b || !b._seenAt) return;
                const elapsed = Date.now() - b._seenAt;
                el.textContent = "上次更新: " + formatRelative(elapsed);
            });
            // Check if any non-stale cards have crossed the 30s threshold (client-side fallback)
            // OR if server stale flag changed since last render
            document.querySelectorAll(".card:not(.card-offline)").forEach((cardEl) => {
                const name = cardEl.dataset.name;
                const b = state.boards.get(name);
                if (!b) return;
                if (b.stale || (b._seenAt && Date.now() - b._seenAt > 30000)) {
                    needRender = true;
                }
            });
            if (needRender) render();
        }, 1000);
    }

    // ---------- Boot ----------

    els.search.addEventListener("input", (ev) => {
        state.filter = ev.target.value.trim().toLowerCase();
        render();
    });

    // Modal wiring: close button, overlay backdrop click, Esc, save, delete, Enter-to-save.
    els.modalClose.addEventListener("click", closeModal);
    els.modal.addEventListener("click", (ev) => {
        // Only close when the overlay itself (not the dialog) is clicked.
        if (ev.target === els.modal) closeModal();
    });
    document.addEventListener("keydown", (ev) => {
        if (ev.key === "Escape" && !els.modal.hidden) closeModal();
    });
    els.saveName.addEventListener("click", saveDisplayName);
    els.deleteBtn.addEventListener("click", deleteBoard);
    els.nameInput.addEventListener("keydown", (ev) => {
        if (ev.key === "Enter") { ev.preventDefault(); saveDisplayName(); }
    });

    tickClock();
    setInterval(tickClock, 1000);

    fetchHealth();
    setInterval(fetchHealth, 5000);

    setInterval(fetchAll, 5000);

    startLastSeenTicker();

    connect();
    fetchAll();
})();
