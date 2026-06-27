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
    };

    const state = {
        boards: new Map(),    // name -> BoardContent
        ws: null,
        backoff: 500,         // ms, doubles on failure, cap 10s
        filter: "",           // current filter query (lower-case)
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
                (msg.boards || []).forEach((b) => state.boards.set(b.name, b));
                render();
                break;
            case "update":
                state.boards.set(msg.board.name, msg.board);
                render();
                break;
            case "remove":
                state.boards.delete(msg.name);
                render();
                break;
            default:
                // unknown — ignore
        }
    }

    // ---------- REST fallback ----------

    async function fetchAll() {
        try {
            const r = await fetch("/api/boards");
            if (!r.ok) return;
            const list = await r.json();
            const next = new Map();
            list.forEach((b) => next.set(b.name, b));
            // Only re-render if something actually changed (cheap shallow compare).
            if (!sameKeys(state.boards, next)) {
                state.boards = next;
                render();
            } else {
                // refresh content in-place (timestamps may have advanced)
                state.boards = next;
            }
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

        // Header: title + live badge
        const head = document.createElement("header");
        head.className = "card-head";

        const title = document.createElement("h3");
        title.className = "card-title";
        title.textContent = b.name;
        title.title = b.name;

        const live = document.createElement("span");
        live.className = "card-live";
        live.innerHTML = '<span class="live-dot"></span>live';

        head.appendChild(title);
        head.appendChild(live);

        // Meta: source-type chip + update time
        const meta = document.createElement("div");
        meta.className = "card-meta";

        if (b.sourceType) {
            const chip = document.createElement("span");
            chip.className = "chip";
            chip.textContent = shortSource(b.sourceType);
            chip.title = b.sourceType;
            meta.appendChild(chip);
        }

        const time = document.createElement("span");
        time.className = "card-time";
        time.textContent = formatTime(b.lastUpdatedMs);
        time.title = "最后更新";
        meta.appendChild(time);

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
        foot.textContent = count + (count === 1 ? " 行" : " 行") + " · 实时更新";

        card.appendChild(head);
        card.appendChild(meta);
        card.appendChild(lines);
        card.appendChild(foot);
        return card;
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

    // ---------- Boot ----------

    els.search.addEventListener("input", (ev) => {
        state.filter = ev.target.value.trim().toLowerCase();
        render();
    });

    tickClock();
    setInterval(tickClock, 1000);

    fetchHealth();
    setInterval(fetchHealth, 5000);

    setInterval(fetchAll, 5000);

    connect();
    fetchAll();
})();
