// Create Web Board — dashboard client
//
// Wire-up:
//   - WebSocket at /ws for live board:update / board:remove events
//   - REST fallback: /api/boards on connect, /api/boards/{name} on select
//   - /api/health polled for ws connection count shown in the topbar
//   - Reconnect with exponential backoff (cap 10s)
//
// Element map (see index.html):
//   #status-dot, #status-text       — WS status pill
//   #board-count, #ws-count, #clock — topbar metrics
//   #search                          — sidebar filter input
//   #board-list                      — <ul> of boards
//   #selected-name, #chips           — main header title + source-type chip
//   #live-badge                      — green "live" pill (shown when a board is selected)
//   #placeholder, #lines             — empty-state svg vs <ol> of <li> per line
//   #updated, #line-count            — footer

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
        boardList: $("board-list"),
        selectedName: $("selected-name"),
        chips: $("chips"),
        liveBadge: $("live-badge"),
        placeholder: $("placeholder"),
        lines: $("lines"),
        updated: $("updated"),
        lineCount: $("line-count"),
    };

    const state = {
        boards: new Map(),    // name -> BoardContent
        selected: null,
        ws: null,
        backoff: 500,         // ms, doubles on failure, cap 10s
        filter: "",           // current sidebar filter query (lower-case)
    };

    // ---------- WebSocket ----------

    function connect() {
        const proto = location.protocol === "https:" ? "wss:" : "ws:";
        const url = proto + "//" + location.host + "/ws";
        const ws = new WebSocket(url);
        state.ws = ws;

        ws.onopen = () => {
            setStatus("ok", "connected");
            state.backoff = 500;
        };

        ws.onmessage = (ev) => {
            let msg;
            try { msg = JSON.parse(ev.data); } catch (_) { return; }
            handleMessage(msg);
        };

        ws.onclose = () => {
            setStatus("off", "disconnected");
            state.ws = null;
            scheduleReconnect();
        };

        ws.onerror = () => {
            // onclose will fire after; reconnect there.
            setStatus("err", "error");
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
                renderSidebar();
                renderSelected();
                break;
            case "update":
                state.boards.set(msg.board.name, msg.board);
                renderSidebar();
                if (state.selected === msg.board.name) renderSelected();
                break;
            case "remove":
                state.boards.delete(msg.name);
                if (state.selected === msg.name) clearSelected();
                renderSidebar();
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
            state.boards.clear();
            list.forEach((b) => state.boards.set(b.name, b));
            renderSidebar();
            if (state.selected) renderSelected();
        } catch (_) { /* offline; WS will heal */ }
    }

    async function fetchOne(name) {
        try {
            const r = await fetch("/api/boards/" + encodeURIComponent(name));
            if (!r.ok) { clearSelected(); return; }
            const b = await r.json();
            state.boards.set(b.name, b);
            renderSelected();
        } catch (_) { /* ignore */ }
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

    function matchesFilter(name, sourceType) {
        if (!state.filter) return true;
        if (name && name.toLowerCase().includes(state.filter)) return true;
        if (sourceType && sourceType.toLowerCase().includes(state.filter)) return true;
        return false;
    }

    function renderSidebar() {
        const all = Array.from(state.boards.values())
            .slice()
            .sort((a, b) => a.name.localeCompare(b.name));
        const visible = all.filter((b) => matchesFilter(b.name, b.sourceType));

        els.boardCount.textContent = String(all.length);

        if (all.length === 0) {
            els.boardList.innerHTML =
                '<li class="empty">' +
                '<span class="empty-icon">⌖</span>' +
                "No boards yet." +
                '<span class="empty-hint">Place a Display Link, open it, and toggle <b>Web: ON</b>.</span>' +
                "</li>";
            return;
        }

        if (visible.length === 0) {
            els.boardList.innerHTML =
                '<li class="empty">' +
                '<span class="empty-icon">∅</span>' +
                "No boards match <b>" + escapeHtml(state.filter) + "</b>." +
                "</li>";
            return;
        }

        // Rebuild list. Cheap enough for hundreds of boards; for thousands we'd diff.
        const frag = document.createDocumentFragment();
        visible.forEach((b) => {
            const li = document.createElement("li");
            li.className = "board-item" + (state.selected === b.name ? " selected" : "");
            li.dataset.name = b.name;

            const n = document.createElement("div");
            n.className = "name";
            n.textContent = b.name;

            const s = document.createElement("span");
            s.className = "source";
            s.textContent = shortSource(b.sourceType);

            li.appendChild(n);
            li.appendChild(s);
            li.addEventListener("click", () => selectBoard(b.name));
            frag.appendChild(li);
        });
        els.boardList.innerHTML = "";
        els.boardList.appendChild(frag);
    }

    function renderSelected() {
        const b = state.boards.get(state.selected);
        if (!b) { clearSelected(); return; }

        els.selectedName.textContent = b.name;

        // Chips: source-type as the single accent chip.
        els.chips.innerHTML = "";
        if (b.sourceType) {
            const chip = document.createElement("span");
            chip.className = "chip accent";
            chip.textContent = shortSource(b.sourceType);
            chip.title = b.sourceType;
            els.chips.appendChild(chip);
        }

        // Live badge: shown whenever a board is selected.
        els.liveBadge.hidden = false;

        // Lines: <li> per line, replacing the old <pre> textContent join.
        els.placeholder.style.display = "none";
        els.lines.innerHTML = "";
        const lines = (b.lines && b.lines.length) ? b.lines : [];
        const frag = document.createDocumentFragment();
        lines.forEach((line) => {
            const li = document.createElement("li");
            li.textContent = line;
            frag.appendChild(li);
        });
        els.lines.appendChild(frag);

        const count = lines.length;
        els.lineCount.textContent = count + (count === 1 ? " line" : " lines");
        els.updated.textContent = "updated " + formatTime(b.lastUpdatedMs);
    }

    function clearSelected() {
        state.selected = null;
        els.selectedName.textContent = "Select a board";
        els.chips.innerHTML = "";
        els.liveBadge.hidden = true;
        els.lines.innerHTML = "";
        els.placeholder.style.display = "";
        els.updated.textContent = "—";
        els.lineCount.textContent = "0 lines";
    }

    function selectBoard(name) {
        state.selected = name;
        renderSidebar();
        renderSelected();
        // Always re-fetch — WS may have missed an update.
        fetchOne(name);
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

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, (c) => ({
            "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
        })[c]);
    }

    function tickClock() {
        const d = new Date();
        const pad = (n) => String(n).padStart(2, "0");
        els.clock.textContent = pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    }

    // ---------- Boot ----------

    els.search.addEventListener("input", (ev) => {
        state.filter = ev.target.value.trim().toLowerCase();
        renderSidebar();
    });

    // Local clock — ticks every second, no server round-trip needed.
    tickClock();
    setInterval(tickClock, 1000);

    // /api/health for ws connection count — poll every 5s (cheap, single JSON object).
    fetchHealth();
    setInterval(fetchHealth, 5000);

    // REST poll for board list every 5s as a safety net (covers missed WS events).
    setInterval(fetchAll, 5000);

    connect();
    fetchAll();
})();
