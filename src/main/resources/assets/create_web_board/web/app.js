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
        // Tag editor
        tagInput: $("modal-tag-input"),
        addTag: $("modal-add-tag"),
        tagChips: $("modal-tag-chips"),
        // Product thumbnails
        pickItems: $("modal-pick-items"),
        itemThumbs: $("modal-item-thumbs"),
        // Group toggle
        groupToggle: $("group-toggle"),
        // View toggle (看板/网络) + new network button
        viewToggle: $("view-toggle"),
        newNetworkBtn: $("new-network-btn"),
        // Item picker modal
        itemPicker: $("item-picker"),
        itemPickerClose: $("item-picker-close"),
        itemPickerSearch: $("item-picker-search"),
        itemPickerGrid: $("item-picker-grid"),
        itemPickerCount: $("item-picker-selected-count"),
        itemPickerClear: $("item-picker-clear"),
        itemPickerConfirm: $("item-picker-confirm"),
        // Network detail modal
        networkModal: $("network-modal"),
        networkModalTitle: $("network-modal-title"),
        networkModalClose: $("network-modal-close"),
        networkNameInput: $("network-name-input"),
        networkSaveName: $("network-save-name"),
        networkModalStats: $("network-modal-stats"),
        networkModalChart: $("network-modal-chart"),
        networkModalChartMeta: $("network-modal-chart-meta"),
        networkModalMembers: $("network-modal-members"),
        networkModalMemberCount: $("network-modal-member-count"),
        networkModalFootMeta: $("network-modal-foot-meta"),
        networkEditBtn: $("network-edit"),
        networkDeleteBtn: $("network-delete"),
        // Network editor modal
        networkEditor: $("network-editor"),
        networkEditorTitle: $("network-editor-title"),
        networkEditorClose: $("network-editor-close"),
        networkEditorName: $("network-editor-name"),
        networkEditorCount: $("network-editor-count"),
        networkEditorMembers: $("network-editor-members"),
        networkEditorAdd: $("network-editor-add"),
        networkEditorCancel: $("network-editor-cancel"),
        networkEditorSave: $("network-editor-save"),
        // Board picker modal
        boardPicker: $("board-picker"),
        boardPickerClose: $("board-picker-close"),
        boardPickerList: $("board-picker-list"),
    };

    const state = {
        boards: new Map(),    // name -> BoardContent
        ws: null,
        backoff: 500,         // ms, doubles on failure, cap 10s
        filter: "",           // current filter query (lower-case)
        lastSeenInterval: null,
        modalName: null,      // stable key of the board currently shown in the modal (null = closed)
        grouped: true,        // cluster cards by tag on the main grid
        itemPickerSelected: null, // Set<String> of item ids pending confirmation (null = picker closed)
        // Stress network feature
        networks: new Map(),  // id -> {id, name, members:[{boardName, role, label, lineIndex}]}
        viewMode: "boards",   // "boards" | "networks"
        networkModalId: null, // id of the network currently shown in the detail modal (null = closed)
        networkEditorState: null, // {id?, name, members:[{boardName, role, label, lineIndex}]} (null = closed)
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
                refreshNetworkModalIfOpen();
                break;
            case "update":
                msg.board._seenAt = Date.now();
                state.boards.set(msg.board.name, msg.board);
                render();
                refreshModalIfOpen();
                refreshNetworkModalIfOpen();
                break;
            case "remove":
                state.boards.delete(msg.name);
                if (state.modalName === msg.name) closeModal();
                render();
                refreshNetworkModalIfOpen();
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
            refreshNetworkModalIfOpen();
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

    // ---------- Stress networks (REST) ----------

    /** Fetch the list of stress networks. Polled every 5s (alongside fetchAll) so live
     *  aggregates stay fresh even without a WS push for networks. */
    async function fetchNetworks() {
        try {
            const r = await fetch("/api/networks");
            if (!r.ok) return;
            const list = await r.json();
            const next = new Map();
            (list || []).forEach((n) => next.set(n.id, n));
            state.networks = next;
            // Re-render the grid if we're in network view; also keep an open modal in sync.
            if (state.viewMode === "networks") render();
            refreshNetworkModalIfOpen();
        } catch (_) { /* offline; WS/next poll will heal */ }
    }

    /** If the network detail modal is open, refresh its live fields from current state. */
    function refreshNetworkModalIfOpen() {
        if (!state.networkModalId) return;
        const net = state.networks.get(state.networkModalId);
        if (net) {
            // Preserve any text the user is currently typing in the name field.
            const focused = document.activeElement === els.networkNameInput;
            const draft = els.networkNameInput.value;
            renderNetworkModalLive(net);
            if (focused) {
                els.networkNameInput.focus();
                els.networkNameInput.value = draft;
            }
        } else {
            // Network was deleted out-of-band (e.g. another tab); close the modal.
            closeNetworkModal();
        }
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
        // Network view: render network cards instead of board cards.
        if (state.viewMode === "networks") {
            renderNetworks();
            return;
        }

        const all = Array.from(state.boards.values()).slice()
                .sort((a, b) => displayTitle(a).localeCompare(displayTitle(b)));
        const visible = all.filter(matchesFilter);

        els.boardCount.textContent = String(all.length);

        // Empty state: show the big friendly placeholder whenever there's nothing to show.
        if (visible.length === 0) {
            els.boardGrid.innerHTML = "";
            els.boardGrid.classList.remove("grouped");
            els.emptyState.hidden = false;
            return;
        }
        els.emptyState.hidden = true;

        // Rebuild the grid. For typical dashboards (tens of boards) a full rebuild is cheap
        // and avoids the bookkeeping of diffing. If this ever scales to hundreds, switch to
        // keyed updates (re-use existing .card nodes by data-name).
        els.boardGrid.innerHTML = "";
        if (state.grouped) {
            els.boardGrid.classList.add("grouped");
            els.boardGrid.appendChild(renderGrouped(visible));
        } else {
            els.boardGrid.classList.remove("grouped");
            const frag = document.createDocumentFragment();
            visible.forEach((b) => frag.appendChild(renderCard(b)));
            els.boardGrid.appendChild(frag);
        }
    }

    /**
     * Cluster visible boards by tag. A board with multiple tags appears in every tag group
     * it belongs to (multi-label clustering — a "冶炼/一楼" board shows under both "冶炼" and
     * "一楼"). Boards with no tags fall into an "未分类" group at the end.
     */
    function renderGrouped(visible) {
        const groups = new Map(); // tag -> [boards]
        const untagged = [];
        visible.forEach((b) => {
            const tags = (b.tags && b.tags.length > 0) ? b.tags : null;
            if (!tags) { untagged.push(b); return; }
            tags.forEach((t) => {
                if (!groups.has(t)) groups.set(t, []);
                groups.get(t).push(b);
            });
        });
        const frag = document.createDocumentFragment();
        Array.from(groups.keys()).sort().forEach((tag) => {
            frag.appendChild(renderGroup(tag, groups.get(tag), false));
        });
        if (untagged.length > 0) {
            frag.appendChild(renderGroup("未分类", untagged, true));
        }
        return frag;
    }

    function renderGroup(title, boards, untagged) {
        const section = document.createElement("section");
        section.className = "group-section" + (untagged ? " untagged" : "");
        const head = document.createElement("div");
        head.className = "group-head";
        const t = document.createElement("h3");
        t.className = "group-title";
        t.textContent = title;
        const cnt = document.createElement("span");
        cnt.className = "group-count";
        cnt.textContent = boards.length + " 个看板";
        t.appendChild(cnt);
        head.appendChild(t);
        section.appendChild(head);
        const grid = document.createElement("div");
        grid.className = "group-grid";
        boards.forEach((b) => grid.appendChild(renderCard(b)));
        section.appendChild(grid);
        return section;
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

        card.appendChild(head);
        card.appendChild(meta);

        // Tags (free-text labels; click a card opens the modal to edit them)
        if (b.tags && b.tags.length > 0) {
            const tags = document.createElement("div");
            tags.className = "card-tags";
            b.tags.forEach((tag) => {
                const chip = document.createElement("span");
                chip.className = "card-tag";
                chip.textContent = tag;
                tags.appendChild(chip);
            });
            card.appendChild(tags);
        }

        // Product thumbnails (resolved server-side from each mod jar's texture)
        if (b.itemIds && b.itemIds.length > 0) {
            const items = document.createElement("div");
            items.className = "card-items";
            b.itemIds.forEach((id) => items.appendChild(cardItemThumb(id)));
            card.appendChild(items);
        }

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

        card.appendChild(lines);
        card.appendChild(foot);
        return card;
    }

    // ---------- Stress network: value extraction & aggregation ----------

    /** Extract the first parseable number from a board's line at the given index.
     *  Reuses the existing NUM_RE so backend/frontend extraction stays in sync. */
    function extractBoardValue(board, lineIndex) {
        if (!board || !board.lines || board.lines.length === 0) return null;
        const idx = Math.min(lineIndex || 0, board.lines.length - 1);
        const m = board.lines[idx].match(NUM_RE);
        return m ? parseFloat(m[0]) : null;
    }

    /** Role display label in Chinese. */
    const ROLE_LABELS = { producer: "产生", consumer: "消耗", storage: "存储" };

    /** Aggregate a network's current values from its member boards. Returns
     *  {production, consumption, storage, surplus, activeMembers, memberValues}. */
    function computeNetworkAggregate(net) {
        let production = 0, consumption = 0, storage = 0, activeMembers = 0;
        const memberValues = [];
        (net.members || []).forEach((m) => {
            const board = state.boards.get(m.boardName);
            const stale = !board || board.stale || (!board._seenAt || Date.now() - board._seenAt > 30000);
            const value = extractBoardValue(board, m.lineIndex);
            if (!stale) activeMembers++;
            memberValues.push({ member: m, board, stale, value });
            if (value == null) return;
            if (m.role === "producer") production += value;
            else if (m.role === "consumer") consumption += value;
            else if (m.role === "storage") storage += value;
        });
        const surplus = production - consumption;
        return { production, consumption, storage, surplus, activeMembers, memberValues };
    }

    // ---------- Stress network: grid rendering ----------

    /** Render the network grid (replaces the board grid when viewMode === "networks"). */
    function renderNetworks() {
        // Toggle the "新建网络" button visibility — only relevant in network view.
        els.newNetworkBtn.hidden = false;

        const all = Array.from(state.networks.values()).slice()
                .sort((a, b) => (a.name || "").localeCompare(b.name || ""));
        const visible = all.filter(matchesNetworkFilter);

        els.boardCount.textContent = String(all.length);

        if (visible.length === 0) {
            els.boardGrid.innerHTML = "";
            els.boardGrid.classList.remove("grouped");
            els.emptyState.hidden = false;
            // Repurpose the empty-state copy briefly for the network view.
            return;
        }
        els.emptyState.hidden = true;

        els.boardGrid.innerHTML = "";
        els.boardGrid.classList.remove("grouped");
        const frag = document.createDocumentFragment();
        visible.forEach((net) => frag.appendChild(renderNetworkCard(net)));
        els.boardGrid.appendChild(frag);
    }

    function matchesNetworkFilter(net) {
        if (!state.filter) return true;
        const name = (net.name || "").toLowerCase();
        if (name.includes(state.filter)) return true;
        // Also match against member board names / labels.
        return (net.members || []).some((m) => {
            const board = state.boards.get(m.boardName);
            const disp = board ? displayTitle(board).toLowerCase() : m.boardName.toLowerCase();
            return disp.includes(state.filter) || (m.label || "").toLowerCase().includes(state.filter);
        });
    }

    /** Render a single network card. Reuses the .card base; adds stats + member list. */
    function renderNetworkCard(net) {
        const card = document.createElement("article");
        card.className = "card net-card";
        card.dataset.id = net.id;
        card.tabIndex = 0;
        card.setAttribute("role", "button");
        card.setAttribute("aria-label", "打开网络详情: " + (net.name || ""));

        const agg = computeNetworkAggregate(net);
        const anyLive = agg.activeMembers > 0;

        card.addEventListener("click", () => openNetworkModal(net.id));
        card.addEventListener("keydown", (ev) => {
            if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); openNetworkModal(net.id); }
        });

        // Header: title + live badge
        const head = document.createElement("header");
        head.className = "card-head";
        const title = document.createElement("h3");
        title.className = "card-title";
        title.textContent = net.name || "（未命名网络）";
        title.title = net.name || "";
        const live = document.createElement("span");
        live.className = "card-live";
        if (anyLive) {
            live.innerHTML = '<span class="live-dot"></span>live';
        } else {
            live.innerHTML = '<span class="live-dot"></span>离线';
            card.classList.add("card-offline");
        }
        head.appendChild(title);
        head.appendChild(live);
        card.appendChild(head);

        // Stats: 产生 / 消耗 / 盈余 (and 存储 row if there are storage members)
        const stats = document.createElement("div");
        stats.className = "net-stats" + (agg.storage !== 0 || (net.members || []).some((m) => m.role === "storage") ? " with-storage" : "");
        stats.appendChild(renderNetStat("产生", agg.production, "produce"));
        stats.appendChild(renderNetStat("消耗", agg.consumption, "consume"));
        const surplusCls = "surplus " + (agg.surplus >= 0 ? "pos" : "neg");
        stats.appendChild(renderNetStat("盈余", agg.surplus, surplusCls));
        if ((net.members || []).some((m) => m.role === "storage")) {
            const row = document.createElement("div");
            row.className = "net-storage-row";
            const lab = document.createElement("span");
            lab.className = "net-stat-label";
            lab.textContent = "存储";
            const val = document.createElement("span");
            val.className = "net-stat-value";
            val.textContent = formatNum(agg.storage);
            row.appendChild(lab);
            row.appendChild(val);
            stats.appendChild(row);
        }
        card.appendChild(stats);

        // Member list
        const members = document.createElement("div");
        members.className = "net-members";
        if (agg.memberValues.length === 0) {
            const empty = document.createElement("div");
            empty.className = "net-editor-empty";
            empty.textContent = "（暂无成员）";
            members.appendChild(empty);
        } else {
            agg.memberValues.forEach((mv) => members.appendChild(renderNetMemberRow(mv)));
        }
        card.appendChild(members);

        // Footer: member count + last update time (latest member update)
        const foot = document.createElement("footer");
        foot.className = "net-card-foot";
        const count = (net.members || []).length;
        foot.textContent = count + (count === 1 ? " 个成员" : " 个成员");
        const time = document.createElement("span");
        time.className = "foot-time";
        let latest = 0;
        agg.memberValues.forEach((mv) => { if (mv.board && mv.board.lastUpdatedMs > latest) latest = mv.board.lastUpdatedMs; });
        time.textContent = latest ? formatTime(latest) : "—";
        time.title = "最后更新";
        foot.appendChild(time);
        card.appendChild(foot);
        return card;
    }

    function renderNetStat(label, value, cls) {
        const stat = document.createElement("div");
        stat.className = "net-stat " + cls;
        const lab = document.createElement("span");
        lab.className = "net-stat-label";
        lab.textContent = label;
        const val = document.createElement("span");
        val.className = "net-stat-value";
        val.textContent = formatNum(value);
        stat.appendChild(lab);
        stat.appendChild(val);
        return stat;
    }

    function renderNetMemberRow(mv) {
        const row = document.createElement("div");
        row.className = "net-member";
        const dot = document.createElement("span");
        dot.className = "member-dot role-" + (mv.member.role || "producer");
        const name = document.createElement("span");
        name.className = "member-name";
        name.textContent = mv.member.label || (mv.board ? displayTitle(mv.board) : mv.member.boardName);
        name.title = mv.member.boardName;
        const badge = document.createElement("span");
        badge.className = "role-badge role-" + (mv.member.role || "producer");
        badge.textContent = ROLE_LABELS[mv.member.role] || mv.member.role;
        const val = document.createElement("span");
        val.className = "member-value" + (mv.stale ? " stale" : "");
        val.textContent = mv.value == null ? "—" : formatNum(mv.value);
        row.appendChild(dot);
        row.appendChild(name);
        row.appendChild(badge);
        row.appendChild(val);
        return row;
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

        // Tags + products (these re-render on every WS update; the name-input draft is preserved
        // separately by refreshModalIfOpen, and the tag input below is not touched here).
        renderTagChips(b);
        renderItemThumbs(b);
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
            frag.appendChild(renderChart(series, { showMean: true, showRate: true, showAnomalies: true }));
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
    function renderChart(series, opts) {
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
            sw.style.background = (opts && opts.colors && opts.colors[idx]) || chartColor(idx);
            const label = document.createElement("span");
            label.className = "legend-label";
            label.textContent = s.label;
            item.appendChild(sw);
            item.appendChild(label);
            legend.appendChild(item);
        });
        wrap.appendChild(legend);

        wrap.appendChild(renderChartSvg(series, opts));
        return wrap;
    }

    /** Hand-drawn SVG line chart. viewBox is fixed; CSS scales it to container width.
     *  opts = {showMean, showRate, showAnomalies, colors}. Returns a wrapper div containing
     *  the SVG (and a rate-of-change annotation below it when showRate is set). */
    function renderChartSvg(series, opts) {
        opts = opts || {};
        const colorFor = (idx) => (opts.colors && opts.colors[idx]) || chartColor(idx);

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

        // Mean line: horizontal dashed line at the average of the FIRST series, with a label.
        if (opts.showMean && series.length > 0 && series[0].points.length > 0) {
            const pts0 = series[0].points;
            const meanValue = pts0.reduce((a, p) => a + p.value, 0) / pts0.length;
            const yMean = yOf(meanValue);
            const yc = Math.max(padT, Math.min(H - padB, yMean));
            const meanColor = colorFor(0);
            parts.push('<line class="chart-mean-line" x1="' + padL + '" y1="' + yc +
                '" x2="' + (W - padR) + '" y2="' + yc + '" stroke="' + meanColor + '"/>');
            parts.push('<text class="chart-mean-label" x="' + (W - padR - 4) + '" y="' + (yc - 4) +
                '" text-anchor="end" fill="' + meanColor + '">均值: ' + formatNum(meanValue) + '</text>');
        }

        // Series polylines + points (with native <title> tooltips on hover)
        series.forEach((s, idx) => {
            const color = colorFor(idx);
            const pts = s.points.map((p) => xOf(p.ts) + "," + yOf(p.value)).join(" ");
            parts.push('<polyline class="chart-line" points="' + pts +
                '" fill="none" stroke="' + color + '"/>');

            // Anomaly detection: for series with >= 6 points, compute per-step deltas and
            // flag points where |delta - meanDelta| > 2*stdDev. Drawn as larger red-ringed dots.
            const anomalyIdx = new Set();
            if (opts.showAnomalies && s.points.length >= 6) {
                const deltas = [];
                for (let i = 1; i < s.points.length; i++) {
                    deltas.push(s.points[i].value - s.points[i - 1].value);
                }
                const meanDelta = deltas.reduce((a, d) => a + d, 0) / deltas.length;
                const variance = deltas.reduce((a, d) => a + (d - meanDelta) * (d - meanDelta), 0) / deltas.length;
                const stdDev = Math.sqrt(variance);
                const threshold = 2 * stdDev;
                for (let i = 0; i < deltas.length; i++) {
                    if (Math.abs(deltas[i] - meanDelta) > threshold) {
                        anomalyIdx.add(i + 1); // mark the point where the anomalous delta arrives
                    }
                }
            }

            s.points.forEach((p, pi) => {
                if (anomalyIdx.has(pi)) {
                    parts.push('<circle class="chart-point chart-anomaly" cx="' + xOf(p.ts) + '" cy="' + yOf(p.value) +
                        '" r="5" fill="' + color + '"><title>' +
                        escapeHtml(s.label) + " · " + formatTime(p.ts) + " · " + formatNum(p.value) + " · 异常" +
                        "</title></circle>");
                } else {
                    parts.push('<circle class="chart-point" cx="' + xOf(p.ts) + '" cy="' + yOf(p.value) +
                        '" r="3" fill="' + color + '"><title>' +
                        escapeHtml(s.label) + " · " + formatTime(p.ts) + " · " + formatNum(p.value) +
                        "</title></circle>");
                }
            });
        });

        parts.push("</svg>");
        const svgHolder = document.createElement("div");
        svgHolder.innerHTML = parts.join("");
        const svgEl = svgHolder.firstElementChild;

        // Rate of change annotation (HTML, below the SVG) — computed from the first series:
        // (lastValue - firstValue) / timeSpanSeconds.
        const wrap = document.createElement("div");
        wrap.appendChild(svgEl);
        if (opts.showRate && series.length > 0 && series[0].points.length >= 2) {
            const s0 = series[0];
            const first = s0.points[0], last = s0.points[s0.points.length - 1];
            const dtSec = (last.ts - first.ts) / 1000;
            let rateText, rateCls;
            if (dtSec <= 0) {
                rateText = "变化率: —";
                rateCls = "rate-flat";
            } else {
                const rate = (last.value - first.value) / dtSec;
                rateText = "变化率: " + (rate > 0 ? "+" : "") + formatNum(rate) + "/s";
                rateCls = rate > 0 ? "rate-up" : (rate < 0 ? "rate-down" : "rate-flat");
            }
            const ann = document.createElement("div");
            ann.className = "chart-rate-annotation";
            const span = document.createElement("span");
            span.className = rateCls;
            span.textContent = rateText;
            ann.appendChild(span);
            wrap.appendChild(ann);
        }
        return wrap;
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

    // ---------- Stress network: detail modal ----------

    function openNetworkModal(id) {
        const net = state.networks.get(id);
        if (!net) return;
        state.networkModalId = id;
        renderNetworkModal(net);
        els.networkModal.hidden = false;
        loadNetworkChart(net);
        setTimeout(() => els.networkNameInput.focus(), 0);
    }

    function closeNetworkModal() {
        state.networkModalId = null;
        els.networkModal.hidden = true;
        els.networkModalChart.innerHTML = "";
    }

    /** Full render on open. */
    function renderNetworkModal(net) {
        els.networkModalTitle.textContent = net.name || "（未命名网络）";
        els.networkNameInput.value = net.name || "";
        renderNetworkModalLive(net);
    }

    /** Re-render the live fields (stats + members + foot meta) from current board state.
     *  Called on open and on every WS/REST update. Preserves the name input draft. */
    function renderNetworkModalLive(net) {
        const agg = computeNetworkAggregate(net);

        // Stats
        els.networkModalStats.innerHTML = "";
        els.networkModalStats.appendChild(renderNetStat("产生", agg.production, "produce"));
        els.networkModalStats.appendChild(renderNetStat("消耗", agg.consumption, "consume"));
        const surplusCls = "surplus " + (agg.surplus >= 0 ? "pos" : "neg");
        els.networkModalStats.appendChild(renderNetStat("盈余", agg.surplus, surplusCls));
        if ((net.members || []).some((m) => m.role === "storage")) {
            const row = document.createElement("div");
            row.className = "net-storage-row";
            const lab = document.createElement("span");
            lab.className = "net-stat-label";
            lab.textContent = "存储";
            const val = document.createElement("span");
            val.className = "net-stat-value";
            val.textContent = formatNum(agg.storage);
            row.appendChild(lab);
            row.appendChild(val);
            els.networkModalStats.appendChild(row);
        }

        // Members
        els.networkModalMembers.innerHTML = "";
        els.networkModalMemberCount.textContent = (net.members || []).length + " 个成员";
        if (agg.memberValues.length === 0) {
            const empty = document.createElement("div");
            empty.className = "net-editor-empty";
            empty.textContent = "（暂无成员）";
            els.networkModalMembers.appendChild(empty);
        } else {
            agg.memberValues.forEach((mv) => els.networkModalMembers.appendChild(renderNetMemberRow(mv)));
        }

        // Foot meta
        let latest = 0;
        agg.memberValues.forEach((mv) => { if (mv.board && mv.board.lastUpdatedMs > latest) latest = mv.board.lastUpdatedMs; });
        els.networkModalFootMeta.textContent = (agg.activeMembers > 0 ? "在线" : "离线") +
            "  ·  " + (net.members || []).length + " 成员  ·  更新于 " + (latest ? formatTime(latest) : "—");
    }

    async function loadNetworkChart(net) {
        els.networkModalChart.innerHTML = '<div class="hist-empty">加载中…</div>';
        els.networkModalChartMeta.textContent = "";
        try {
            const series = await buildNetworkSeries(net);
            if (series.length === 0) {
                els.networkModalChart.innerHTML = '<div class="hist-empty">（暂无可绘制的趋势数据）</div>';
                return;
            }
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
                sw.style.background = networkChartColor(idx);
                const label = document.createElement("span");
                label.className = "legend-label";
                label.textContent = s.label;
                item.appendChild(sw);
                item.appendChild(label);
                legend.appendChild(item);
            });
            wrap.appendChild(legend);
            const opts = { showMean: true, showRate: true, showAnomalies: true, colors: series.map((_, i) => networkChartColor(i)) };
            wrap.appendChild(renderChartSvg(series, opts));
            els.networkModalChart.innerHTML = "";
            els.networkModalChart.appendChild(wrap);
            const totalPoints = series.reduce((n, s) => n + s.points.length, 0);
            els.networkModalChartMeta.textContent = totalPoints + " 点";
        } catch (_) {
            els.networkModalChart.innerHTML = '<div class="hist-empty">加载失败</div>';
        }
    }

    /** Fetch each member's history, extract values per role, forward-fill missing points,
     *  and aggregate by timestamp into 3 series: production (产生), consumption (消耗), surplus (盈余).
     *  Returns [{label, points:[{ts, value}]}, ...] (only series with >= 2 points). */
    async function buildNetworkSeries(net) {
        const members = net.members || [];
        if (members.length === 0) return [];

        // Fetch all member histories in parallel.
        const results = await Promise.all(members.map(async (m) => {
            try {
                const r = await fetch("/api/boards/" + encodeURIComponent(m.boardName) + "/history");
                if (!r.ok) return { member: m, history: [] };
                const list = await r.json();
                return { member: m, history: list || [] };
            } catch (_) {
                return { member: m, history: [] };
            }
        }));

        // Collect the union of all timestamps across all member histories.
        const tsSet = new Set();
        results.forEach(({ history }) => {
            history.forEach((he) => { if (he && he.ts != null) tsSet.add(he.ts); });
        });
        const timestamps = Array.from(tsSet).sort((a, b) => a - b);
        if (timestamps.length < 2) return [];

        // For each member, build a ts -> value map (extracted via extractBoardValue).
        // Then for each timestamp, forward-fill the member's value (use last known).
        const memberSeries = results.map(({ member, history }) => {
            const map = new Map(); // ts -> value
            history.forEach((he) => {
                const v = extractBoardValue({ lines: he.lines }, member.lineIndex);
                if (v != null) map.set(he.ts, v);
            });
            return { member, map };
        });

        // Walk timestamps in order, forward-filling each member's value.
        const lastVals = memberSeries.map(() => null);
        const prodPts = [], consPts = [], storPts = [];
        timestamps.forEach((ts, ti) => {
            let prod = 0, cons = 0, stor = 0;
            let prodKnown = false, consKnown = false, storKnown = false;
            memberSeries.forEach((ms, i) => {
                if (ms.map.has(ts)) lastVals[i] = ms.map.get(ts);
                const v = lastVals[i];
                if (v == null) return;
                if (ms.member.role === "producer") { prod += v; prodKnown = true; }
                else if (ms.member.role === "consumer") { cons += v; consKnown = true; }
                else if (ms.member.role === "storage") { stor += v; storKnown = true; }
            });
            // Only emit a point once at least one contributing member has been seen.
            // (Avoids a flat zero ramp before the first real sample.)
            if (prodKnown || consKnown) {
                prodPts.push({ ts, value: prod });
                consPts.push({ ts, value: cons });
            }
            if (storKnown) storPts.push({ ts, value: stor });
        });

        const series = [];
        if (prodPts.length >= 2) series.push({ label: "产生", points: prodPts });
        if (consPts.length >= 2) series.push({ label: "消耗", points: consPts });
        // Surplus = production - consumption, computed at each common timestamp.
        if (prodPts.length >= 2 || consPts.length >= 2) {
            const surplusPts = [];
            const prodMap = new Map(prodPts.map((p) => [p.ts, p.value]));
            const consMap = new Map(consPts.map((p) => [p.ts, p.value]));
            // Union of prod/cons timestamps (both share the same set in our construction).
            const unionTs = new Set([...prodMap.keys(), ...consMap.keys()]);
            Array.from(unionTs).sort((a, b) => a - b).forEach((ts) => {
                const p = prodMap.has(ts) ? prodMap.get(ts) : 0;
                const c = consMap.has(ts) ? consMap.get(ts) : 0;
                surplusPts.push({ ts, value: p - c });
            });
            if (surplusPts.length >= 2) series.push({ label: "盈余", points: surplusPts });
        }
        if (storPts.length >= 2) series.push({ label: "存储", points: storPts });
        return series;
    }

    /** Fixed palette for the network trend chart so colors are stable: green=production,
     *  orange=consumption, blue=surplus, then fall back to the standard palette. */
    function networkChartColor(i) {
        const palette = ["#4ec9b0", "#FAA21B", "#5aa9ff", "#c084fc", "#f48771", "#facc15"];
        return palette[i % palette.length];
    }

    async function saveNetworkName() {
        const id = state.networkModalId;
        if (!id) return;
        const net = state.networks.get(id);
        if (!net) return;
        const value = els.networkNameInput.value.trim();
        els.networkSaveName.disabled = true;
        try {
            const body = JSON.stringify({ name: value, members: net.members || [] });
            const r = await fetch("/api/networks/" + encodeURIComponent(id), {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: body,
            });
            if (r.ok) {
                net.name = value;
                state.networks.set(id, net);
                render();
                renderNetworkModal(net);
                await fetchNetworks();
            }
        } catch (_) { /* ignore */ } finally {
            els.networkSaveName.disabled = false;
        }
    }

    async function deleteNetwork() {
        const id = state.networkModalId;
        if (!id) return;
        const net = state.networks.get(id);
        const name = net ? (net.name || "（未命名网络）") : "此网络";
        if (!window.confirm("确认删除网络「" + name + "」？此操作不可撤销。")) return;
        els.networkDeleteBtn.disabled = true;
        try {
            const r = await fetch("/api/networks/" + encodeURIComponent(id), { method: "DELETE" });
            if (r.ok) {
                state.networks.delete(id);
                closeNetworkModal();
                await fetchNetworks();
            }
        } catch (_) { /* ignore */ } finally {
            els.networkDeleteBtn.disabled = false;
        }
    }

    // ---------- Card item thumbnail (small, for the grid card) ----------

    /** Small 22px thumbnail for a product item on a card. Falls back to "?" on 404. */
    function cardItemThumb(itemId) {
        const wrap = document.createElement("span");
        wrap.className = "card-item-thumb";
        const img = document.createElement("img");
        img.src = "/api/item-icon/" + encodeURIComponent(itemId);
        img.alt = itemId;
        img.title = itemId;
        img.loading = "lazy";
        img.addEventListener("error", () => {
            wrap.classList.add("empty");
            wrap.textContent = "?";
            if (img.parentNode === wrap) wrap.removeChild(img);
        });
        wrap.appendChild(img);
        return wrap;
    }

    // ---------- Tag editor (modal) ----------

    function renderTagChips(b) {
        els.tagChips.innerHTML = "";
        (b.tags || []).forEach((tag) => {
            const chip = document.createElement("span");
            chip.className = "tag-chip";
            const label = document.createElement("span");
            label.textContent = tag;
            const rm = document.createElement("button");
            rm.className = "tag-chip-remove";
            rm.textContent = "×";
            rm.title = "移除标签";
            rm.setAttribute("aria-label", "移除标签 " + tag);
            rm.addEventListener("click", () => removeTag(tag));
            chip.appendChild(label);
            chip.appendChild(rm);
            els.tagChips.appendChild(chip);
        });
    }

    function addTag() {
        const name = state.modalName;
        if (!name) return;
        const b = state.boards.get(name);
        if (!b) return;
        const val = els.tagInput.value.trim();
        if (!val) return;
        const tags = (b.tags || []).slice();
        if (tags.includes(val)) { els.tagInput.value = ""; return; }
        tags.push(val);
        els.tagInput.value = "";
        saveTags(tags);
    }

    function removeTag(tag) {
        const name = state.modalName;
        if (!name) return;
        const b = state.boards.get(name);
        if (!b) return;
        saveTags((b.tags || []).filter((t) => t !== tag));
    }

    async function saveTags(tags) {
        const name = state.modalName;
        if (!name) return;
        try {
            const r = await fetch("/api/boards/" + encodeURIComponent(name) + "/tags", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ tags: tags }),
            });
            if (r.ok) {
                const b = state.boards.get(name);
                if (b) {
                    b.tags = tags;
                    render();
                    renderModal(b);
                }
            }
        } catch (_) { /* ignore */ }
    }

    // ---------- Product thumbnails (modal) ----------

    /** Cache of itemId -> localized name (e.g. "minecraft:iron_ingot" -> "铁锭"). Populated
     *  lazily from /api/items/names so modal thumbs show the same Chinese name the picker did. */
    const itemNameCache = new Map();

    async function fetchItemNames(ids) {
        const missing = ids.filter((id) => !itemNameCache.has(id));
        if (missing.length === 0) return;
        try {
            const r = await fetch("/api/items/names", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ items: missing }),
            });
            if (!r.ok) return;
            const map = await r.json();
            Object.keys(map).forEach((id) => itemNameCache.set(id, map[id]));
        } catch (_) { /* ignore — thumbs fall back to short id */ }
    }

    async function renderItemThumbs(b) {
        els.itemThumbs.innerHTML = "";
        const ids = b.itemIds || [];
        // Render immediately with short-id placeholders, then swap in localized names once
        // the bulk-resolve resolves. Keeps the modal responsive even on slow networks.
        const thumbs = renderThumbsSync(ids);
        // After names arrive, update just the name spans (no full re-render — preserves the
        // user's hover/focus state on the remove buttons).
        fetchItemNames(ids).then(() => {
            thumbs.forEach(({ id, nameEl }) => {
                const name = itemNameCache.get(id);
                if (name) nameEl.textContent = name;
            });
        });
    }

    function renderThumbsSync(ids) {
        const thumbs = [];
        ids.forEach((id) => {
            const thumb = document.createElement("span");
            thumb.className = "item-thumb";
            const img = document.createElement("img");
            img.src = "/api/item-icon/" + encodeURIComponent(id);
            img.alt = id;
            img.title = id;
            img.loading = "lazy";
            img.addEventListener("error", () => {
                const ph = document.createElement("span");
                ph.className = "item-thumb-empty";
                ph.textContent = "?";
                if (img.parentNode === thumb) thumb.replaceChild(ph, img);
            });
            const nameEl = document.createElement("span");
            nameEl.className = "item-thumb-name";
            // Show cached localized name if we have it; otherwise short id until the
            // bulk-resolve arrives and updates this span.
            nameEl.textContent = itemNameCache.get(id) || shortItemId(id);
            const rm = document.createElement("button");
            rm.className = "item-thumb-remove";
            rm.textContent = "×";
            rm.title = "移除产物";
            rm.setAttribute("aria-label", "移除产物 " + id);
            rm.addEventListener("click", () => removeItem(id));
            thumb.appendChild(img);
            thumb.appendChild(nameEl);
            thumb.appendChild(rm);
            els.itemThumbs.appendChild(thumb);
            thumbs.push({ id, nameEl });
        });
        return thumbs;
    }

    function removeItem(itemId) {
        const name = state.modalName;
        if (!name) return;
        const b = state.boards.get(name);
        if (!b) return;
        saveItems((b.itemIds || []).filter((x) => x !== itemId));
    }

    async function saveItems(itemIds) {
        const name = state.modalName;
        if (!name) return;
        try {
            const r = await fetch("/api/boards/" + encodeURIComponent(name) + "/items", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ itemIds: itemIds }),
            });
            if (r.ok) {
                const b = state.boards.get(name);
                if (b) {
                    b.itemIds = itemIds;
                    render();
                    renderModal(b);
                }
            }
        } catch (_) { /* ignore */ }
    }

    function shortItemId(id) {
        const i = id.indexOf(":");
        return i >= 0 ? id.slice(i + 1) : id;
    }

    // ---------- Item picker (modal-over-modal) ----------

    let itemSearchTimer = null;

    function openItemPicker() {
        const name = state.modalName;
        if (!name) return;
        const b = state.boards.get(name);
        state.itemPickerSelected = new Set(b ? (b.itemIds || []) : []);
        els.itemPicker.hidden = false;
        els.itemPickerSearch.value = "";
        updatePickerCount();
        searchItems("");
        setTimeout(() => els.itemPickerSearch.focus(), 0);
    }

    function closeItemPicker() {
        els.itemPicker.hidden = true;
        state.itemPickerSelected = null;
    }

    function onItemSearchInput(val) {
        clearTimeout(itemSearchTimer);
        itemSearchTimer = setTimeout(() => searchItems(val), 200);
    }

    async function searchItems(q) {
        els.itemPickerGrid.innerHTML = '<div class="item-picker-empty">加载中…</div>';
        try {
            const url = "/api/items/search?limit=200" + (q ? "&q=" + encodeURIComponent(q) : "");
            const r = await fetch(url);
            if (!r.ok) {
                els.itemPickerGrid.innerHTML = '<div class="item-picker-empty">加载失败</div>';
                return;
            }
            const list = await r.json();
            renderItemPickerGrid(list);
        } catch (_) {
            els.itemPickerGrid.innerHTML = '<div class="item-picker-empty">加载失败</div>';
        }
    }

    function renderItemPickerGrid(list) {
        els.itemPickerGrid.innerHTML = "";
        if (!list || list.length === 0) {
            els.itemPickerGrid.innerHTML = '<div class="item-picker-empty">无匹配物品</div>';
            return;
        }
        const frag = document.createDocumentFragment();
        list.forEach((info) => {
            const card = document.createElement("div");
            const sel = state.itemPickerSelected && state.itemPickerSelected.has(info.id);
            card.className = "item-card" + (sel ? " selected" : "");
            card.dataset.id = info.id;
            const img = document.createElement("img");
            img.src = "/api/item-icon/" + encodeURIComponent(info.id);
            img.alt = info.name || info.id;
            img.loading = "lazy";
            img.addEventListener("error", () => {
                const ph = document.createElement("span");
                ph.className = "item-card-icon";
                ph.textContent = "?";
                if (img.parentNode === card) card.replaceChild(ph, img);
            });
            const nameEl = document.createElement("span");
            nameEl.className = "item-card-name";
            // Show the localized name (e.g. "铁锭") as the primary label; tooltip carries the
            // full registry id for debugging / disambiguation.
            nameEl.textContent = info.name || info.id;
            nameEl.title = info.id;
            card.appendChild(img);
            card.appendChild(nameEl);
            card.addEventListener("click", () => toggleItem(info.id));
            frag.appendChild(card);
        });
        els.itemPickerGrid.appendChild(frag);
    }

    function toggleItem(id) {
        if (!state.itemPickerSelected) return;
        if (state.itemPickerSelected.has(id)) state.itemPickerSelected.delete(id);
        else state.itemPickerSelected.add(id);
        // Update just the toggled card's class — avoid a full grid re-render (keeps scroll position).
        Array.from(els.itemPickerGrid.children).forEach((card) => {
            if (card.dataset && card.dataset.id === id) {
                card.classList.toggle("selected", state.itemPickerSelected.has(id));
            }
        });
        updatePickerCount();
    }

    function updatePickerCount() {
        const n = state.itemPickerSelected ? state.itemPickerSelected.size : 0;
        els.itemPickerCount.textContent = "已选 " + n + " 个";
    }

    function clearItemPicker() {
        if (state.itemPickerSelected) state.itemPickerSelected.clear();
        els.itemPickerGrid.querySelectorAll(".item-card.selected").forEach((c) => c.classList.remove("selected"));
        updatePickerCount();
    }

    async function confirmItems() {
        const name = state.modalName;
        if (!name || !state.itemPickerSelected) return;
        const itemIds = Array.from(state.itemPickerSelected);
        closeItemPicker();
        await saveItems(itemIds);
    }

    // ---------- Stress network: editor (modal-over-modal) ----------

    /** Open the editor. Pass an existing net to edit it, or omit to create a new one. */
    function openNetworkEditor(net) {
        if (net) {
            state.networkEditorState = {
                id: net.id,
                name: net.name || "",
                members: (net.members || []).map((m) => ({
                    boardName: m.boardName,
                    role: m.role || "producer",
                    label: m.label || "",
                    lineIndex: m.lineIndex || 0,
                })),
            };
            els.networkEditorTitle.textContent = "编辑网络";
        } else {
            state.networkEditorState = {
                name: "",
                members: [],
            };
            els.networkEditorTitle.textContent = "新建网络";
        }
        els.networkEditorName.value = state.networkEditorState.name;
        renderNetworkEditor();
        els.networkEditor.hidden = false;
        setTimeout(() => els.networkEditorName.focus(), 0);
    }

    function closeNetworkEditor() {
        state.networkEditorState = null;
        els.networkEditor.hidden = true;
        // Also dismiss the board picker if it was open on top.
        if (!els.boardPicker.hidden) closeBoardPicker();
    }

    /** Render the editor member list from state.networkEditorState. Each row lets the user
     *  edit the role / label / lineIndex for a member board. */
    function renderNetworkEditor() {
        const st = state.networkEditorState;
        if (!st) return;
        els.networkEditorCount.textContent = st.members.length + " 个成员";
        els.networkEditorMembers.innerHTML = "";
        if (st.members.length === 0) {
            const empty = document.createElement("div");
            empty.className = "net-editor-empty";
            empty.textContent = "（暂无成员，点击「添加看板」选择）";
            els.networkEditorMembers.appendChild(empty);
            return;
        }
        st.members.forEach((m, idx) => {
            els.networkEditorMembers.appendChild(renderEditorMemberRow(m, idx));
        });
    }

    function renderEditorMemberRow(m, idx) {
        const board = state.boards.get(m.boardName);
        const dispName = board ? displayTitle(board) : m.boardName;
        const row = document.createElement("div");
        row.className = "net-member";

        const inner = document.createElement("div");
        inner.className = "net-member-editor-row";

        const dot = document.createElement("span");
        dot.className = "member-dot role-" + (m.role || "producer");

        const boardName = document.createElement("span");
        boardName.className = "net-member-board";
        boardName.textContent = dispName;
        boardName.title = m.boardName;

        const roleSel = document.createElement("select");
        roleSel.className = "net-member-role";
        roleSel.innerHTML = '<option value="producer">产生</option>' +
            '<option value="consumer">消耗</option>' +
            '<option value="storage">存储</option>';
        roleSel.value = m.role || "producer";
        roleSel.addEventListener("change", () => {
            const st = state.networkEditorState;
            if (!st) return;
            st.members[idx].role = roleSel.value;
            dot.className = "member-dot role-" + (roleSel.value || "producer");
        });

        const labelInput = document.createElement("input");
        labelInput.className = "net-member-label-input";
        labelInput.type = "text";
        labelInput.placeholder = "标签（可选）";
        labelInput.value = m.label || "";
        labelInput.maxLength = 32;
        labelInput.addEventListener("input", () => {
            const st = state.networkEditorState;
            if (!st) return;
            st.members[idx].label = labelInput.value;
        });

        const lineInput = document.createElement("input");
        lineInput.className = "net-member-line-input";
        lineInput.type = "number";
        lineInput.min = "0";
        lineInput.step = "1";
        lineInput.placeholder = "行";
        lineInput.title = "提取看板第几行的数值（从 0 开始）";
        lineInput.value = String(m.lineIndex || 0);
        lineInput.addEventListener("input", () => {
            const st = state.networkEditorState;
            if (!st) return;
            const v = parseInt(lineInput.value, 10);
            st.members[idx].lineIndex = isNaN(v) || v < 0 ? 0 : v;
        });

        const rm = document.createElement("button");
        rm.className = "net-member-remove";
        rm.textContent = "×";
        rm.title = "移除成员";
        rm.setAttribute("aria-label", "移除成员 " + dispName);
        rm.addEventListener("click", () => {
            const st = state.networkEditorState;
            if (!st) return;
            st.members.splice(idx, 1);
            renderNetworkEditor();
        });

        inner.appendChild(dot);
        inner.appendChild(boardName);
        inner.appendChild(roleSel);
        inner.appendChild(labelInput);
        inner.appendChild(lineInput);
        inner.appendChild(rm);
        row.appendChild(inner);
        return row;
    }

    async function saveNetwork() {
        const st = state.networkEditorState;
        if (!st) return;
        const name = els.networkEditorName.value.trim();
        if (!name) {
            els.networkEditorName.focus();
            return;
        }
        // Sanitize members: drop any with empty boardName; coerce types.
        const members = st.members
            .filter((m) => m && m.boardName)
            .map((m) => ({
                boardName: m.boardName,
                role: (m.role === "consumer" || m.role === "storage") ? m.role : "producer",
                label: m.label || "",
                lineIndex: Math.max(0, parseInt(m.lineIndex, 10) || 0),
            }));
        const body = JSON.stringify({ name: name, members: members });
        els.networkEditorSave.disabled = true;
        try {
            const method = st.id ? "PUT" : "POST";
            const url = st.id ? "/api/networks/" + encodeURIComponent(st.id) : "/api/networks";
            const r = await fetch(url, {
                method: method,
                headers: { "Content-Type": "application/json" },
                body: body,
            });
            if (r.ok) {
                closeNetworkEditor();
                await fetchNetworks();
            }
        } catch (_) { /* ignore */ } finally {
            els.networkEditorSave.disabled = false;
        }
    }

    // ---------- Stress network: board picker (modal-over-modal-over-modal) ----------

    function openBoardPicker() {
        if (!state.networkEditorState) return;
        renderBoardPickerList();
        els.boardPicker.hidden = false;
    }

    function closeBoardPicker() {
        els.boardPicker.hidden = true;
    }

    /** List all boards not already in the network as pickable cards. */
    function renderBoardPickerList() {
        const st = state.networkEditorState;
        els.boardPickerList.innerHTML = "";
        const all = Array.from(state.boards.values()).slice()
                .sort((a, b) => displayTitle(a).localeCompare(displayTitle(b)));
        if (all.length === 0) {
            const empty = document.createElement("div");
            empty.className = "board-picker-empty";
            empty.textContent = "暂无可用看板";
            els.boardPickerList.appendChild(empty);
            return;
        }
        const existing = new Set(st ? st.members.map((m) => m.boardName) : []);
        const frag = document.createDocumentFragment();
        all.forEach((b) => {
            const card = document.createElement("div");
            card.className = "board-picker-card";
            if (existing.has(b.name)) card.classList.add("selected");
            const name = document.createElement("span");
            name.className = "picker-name";
            name.textContent = displayTitle(b);
            name.title = b.name;
            const src = document.createElement("span");
            src.className = "picker-source";
            src.textContent = b.sourceLabel || shortSource(b.sourceType) || "board";
            const add = document.createElement("span");
            add.className = "picker-add";
            add.textContent = existing.has(b.name) ? "已添加" : "+ 添加";
            card.appendChild(name);
            card.appendChild(src);
            card.appendChild(add);
            card.addEventListener("click", () => {
                if (!state.networkEditorState) return;
                if (existing.has(b.name)) return; // already a member
                state.networkEditorState.members.push({
                    boardName: b.name,
                    role: "producer",
                    label: "",
                    lineIndex: 0,
                });
                closeBoardPicker();
                renderNetworkEditor();
            });
            frag.appendChild(card);
        });
        els.boardPickerList.appendChild(frag);
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
    // Esc 关闭最上层的弹窗（modal-over-modal 优先）：board picker > item picker >
    // network editor > network modal > board modal.
    document.addEventListener("keydown", (ev) => {
        if (ev.key !== "Escape") return;
        if (!els.boardPicker.hidden) closeBoardPicker();
        else if (!els.itemPicker.hidden) closeItemPicker();
        else if (!els.networkEditor.hidden) closeNetworkEditor();
        else if (!els.networkModal.hidden) closeNetworkModal();
        else if (!els.modal.hidden) closeModal();
    });
    els.saveName.addEventListener("click", saveDisplayName);
    els.deleteBtn.addEventListener("click", deleteBoard);
    els.nameInput.addEventListener("keydown", (ev) => {
        if (ev.key === "Enter") { ev.preventDefault(); saveDisplayName(); }
    });

    // View toggle (看板 / 网络): switches the main grid between board cards and network cards.
    els.viewToggle.addEventListener("click", () => {
        state.viewMode = state.viewMode === "networks" ? "boards" : "networks";
        els.viewToggle.setAttribute("aria-pressed", String(state.viewMode === "networks"));
        // "新建网络" button is only meaningful in network view.
        els.newNetworkBtn.hidden = state.viewMode !== "networks";
        render();
    });

    // Group toggle: cluster cards by tag on the main grid (boards view only).
    els.groupToggle.addEventListener("click", () => {
        state.grouped = !state.grouped;
        els.groupToggle.setAttribute("aria-pressed", String(state.grouped));
        render();
    });

    // New network button: opens the editor with a blank network.
    els.newNetworkBtn.addEventListener("click", () => openNetworkEditor(null));

    // Tag editor: Enter or 添加 button appends a tag.
    els.addTag.addEventListener("click", addTag);
    els.tagInput.addEventListener("keydown", (ev) => {
        if (ev.key === "Enter") { ev.preventDefault(); addTag(); }
    });

    // Item picker (modal-over-modal): open / close / search / clear / confirm.
    els.pickItems.addEventListener("click", openItemPicker);
    els.itemPickerClose.addEventListener("click", closeItemPicker);
    els.itemPicker.addEventListener("click", (ev) => {
        // Only close when the overlay itself (not the dialog) is clicked.
        if (ev.target === els.itemPicker) closeItemPicker();
    });
    els.itemPickerSearch.addEventListener("input", (ev) => onItemSearchInput(ev.target.value));
    els.itemPickerClear.addEventListener("click", clearItemPicker);
    els.itemPickerConfirm.addEventListener("click", confirmItems);

    // Network detail modal: close, overlay backdrop click, rename, delete, edit, Enter-to-save.
    els.networkModalClose.addEventListener("click", closeNetworkModal);
    els.networkModal.addEventListener("click", (ev) => {
        if (ev.target === els.networkModal) closeNetworkModal();
    });
    els.networkSaveName.addEventListener("click", saveNetworkName);
    els.networkDeleteBtn.addEventListener("click", deleteNetwork);
    els.networkEditBtn.addEventListener("click", () => {
        const id = state.networkModalId;
        if (!id) return;
        const net = state.networks.get(id);
        if (net) openNetworkEditor(net);
    });
    els.networkNameInput.addEventListener("keydown", (ev) => {
        if (ev.key === "Enter") { ev.preventDefault(); saveNetworkName(); }
    });

    // Network editor (modal-over-modal): close, overlay backdrop click, add board, cancel, save.
    els.networkEditorClose.addEventListener("click", closeNetworkEditor);
    els.networkEditor.addEventListener("click", (ev) => {
        if (ev.target === els.networkEditor) closeNetworkEditor();
    });
    els.networkEditorAdd.addEventListener("click", openBoardPicker);
    els.networkEditorCancel.addEventListener("click", closeNetworkEditor);
    els.networkEditorSave.addEventListener("click", saveNetwork);

    // Board picker (modal-over-modal): close, overlay backdrop click.
    els.boardPickerClose.addEventListener("click", closeBoardPicker);
    els.boardPicker.addEventListener("click", (ev) => {
        if (ev.target === els.boardPicker) closeBoardPicker();
    });

    tickClock();
    setInterval(tickClock, 1000);

    fetchHealth();
    setInterval(fetchHealth, 5000);

    setInterval(fetchAll, 5000);

    // Stress networks: poll every 5s (alongside fetchAll) so live aggregates stay fresh.
    fetchNetworks();
    setInterval(fetchNetworks, 5000);

    startLastSeenTicker();

    connect();
    fetchAll();
})();
