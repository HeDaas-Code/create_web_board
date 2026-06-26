// Create Web Board — dashboard client
// - WebSocket at /ws for live board:update / board:remove events
// - REST fallback: /api/boards on connect, /api/boards/{name} on click
// - Reconnect with exponential backoff (cap 10s)

(function () {
    "use strict";

    const $ = (id) => document.getElementById(id);
    const els = {
        statusDot: $("status-dot"),
        statusText: $("status-text"),
        meta: $("meta"),
        boardList: $("board-list"),
        boardCount: $("board-count"),
        selectedName: $("selected-name"),
        selectedSource: $("selected-source"),
        boardContent: $("board-content"),
        updated: $("updated"),
    };

    const state = {
        boards: new Map(),    // name -> BoardContent
        selected: null,
        ws: null,
        backoff: 500,         // ms, doubles on failure, cap 10s
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
                // Server initial snapshot: { type:"snapshot", boards:[...] }
                state.boards.clear();
                (msg.boards || []).forEach((b) => state.boards.set(b.name, b));
                renderSidebar();
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

    // ---------- Rendering ----------

    function setStatus(kind, text) {
        els.statusDot.className = "dot dot-" + (kind === "ok" ? "ok" : kind === "err" ? "err" : "off");
        els.statusText.textContent = text;
    }

    function renderSidebar() {
        const names = Array.from(state.boards.keys()).sort();
        els.boardCount.textContent = String(names.length);
        els.meta.textContent = names.length + " board" + (names.length === 1 ? "" : "s") + " · 1 ws";

        if (names.length === 0) {
            els.boardList.innerHTML = '<li class="empty">No boards yet. Place a Display Link in-world.</li>';
            return;
        }

        els.boardList.innerHTML = "";
        names.forEach((name) => {
            const b = state.boards.get(name);
            const li = document.createElement("li");
            li.className = "board-item" + (state.selected === name ? " selected" : "");
            li.dataset.name = name;
            const n = document.createElement("div");
            n.className = "name";
            n.textContent = name;
            const s = document.createElement("span");
            s.className = "source";
            s.textContent = b.sourceType || "";
            li.appendChild(n);
            li.appendChild(s);
            li.addEventListener("click", () => selectBoard(name));
            els.boardList.appendChild(li);
        });
    }

    function selectBoard(name) {
        state.selected = name;
        renderSidebar();
        renderSelected();
        // Always re-fetch — WS may have missed an update.
        fetchOne(name);
    }

    function renderSelected() {
        const b = state.boards.get(state.selected);
        if (!b) { clearSelected(); return; }
        els.selectedName.textContent = b.name;
        els.selectedSource.textContent = b.sourceType || "";
        els.boardContent.textContent = (b.lines && b.lines.length)
            ? b.lines.join("\n")
            : "(empty)";
        els.updated.textContent = "updated " + formatTime(b.lastUpdatedMs);
    }

    function clearSelected() {
        state.selected = null;
        els.selectedName.textContent = "Select a board";
        els.selectedSource.textContent = "";
        els.boardContent.textContent = "// click a board on the left to view its live content";
        els.updated.textContent = "—";
    }

    function formatTime(ms) {
        if (!ms) return "—";
        const d = new Date(ms);
        const pad = (n) => String(n).padStart(2, "0");
        return pad(d.getHours()) + ":" + pad(d.getMinutes()) + ":" + pad(d.getSeconds());
    }

    // ---------- Boot ----------

    // REST poll every 5s as a safety net (cheap, covers missed WS events).
    setInterval(fetchAll, 5000);

    connect();
    fetchAll();
})();
