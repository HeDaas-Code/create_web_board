# 火车调度图集成可行性调研

> 调研日期：2026-06-28
> 调研对象：Create 6.0.10（1.21.1 NeoForge）火车系统 + Create: Steam 'n' Rails（汽鸣铁道）+ Create Railways Navigator（CRN）
> 调研目的：评估在 `create_web_board` Web 看板中新增"火车调度图"视图的可行性，展示铁道拓扑、站点、在线列车、列车信息与时刻表，并支持配置货运/客运属性；与上述 mod 联动而非硬依赖。
> 调研方法：基于官方 GitHub 源码（`mc1.21.1/dev` / `mc/1.21.1-DL3` 分支）、CurseForge/Modrinth、官方 wiki，交叉验证。源码链接见文末 Sources。

---

## 1. 执行摘要

**结论：可行，且推荐做。** 三个 mod 在 1.21.1 NeoForge + Create 6 环境下可原生共存，无需跨版本联动。调度图所需的数据（轨道拓扑、站点、列车位置、时刻表、ETA）在服务端可通过 Create 原生 API 完整读取；CRN 额外提供线路/分类/站点标签等元数据和到发预测事件，可作可选增强。

但调研发现了**两个必须先处理的现存问题**（影响你们已有的代码）：

1. `SourceLabels.java` 里 `createrailwaysnavigator:train_display` 这个源 id **在 1.21.1 上不存在**——CRN 1.21.1 注册的 DisplaySource id 是 `createrailwaysnavigator:advanced_display` [7][8]。需要修正，否则该标签永远命中不了。
2. **现有文本镜像通道对 CRN 这个源完全失效**：CRN 的 `AdvancedDisplaySource.provideText()` 直接 `return EMPTY`，它只在 `sourceConfig` 写一个 `AdvancedDisplay=true` 标记，真正数据由 DisplayTarget 端自行计算 [8][9]。你们 `DisplaySourceTransferMixin` wrap `provideText` 的方式对它抓不到任何内容。要拿 CRN 数据必须走事件总线或直接调用其工具类，而非文本镜像。

货运/客运分类 Create 原生不存在，需我们自己在网络层持久化（类似已有的 `webboard-networks.json`），挂到 `Train.id`（UUID）上。

---

## 2. 三个 mod 的身份与版本现状

| 维度 | Create | Steam 'n' Rails（汽鸣铁道） | Create Railways Navigator（CRN） |
|---|---|---|---|
| 角色 | 基础 mod（你们已依赖 `[6.0.10,)`） | Create 官方扩展包，物理铁道扩展 | Create 信息/导航层 addon |
| 开发团队 | Creators-of-Create | **IThundxr / Layers-of-Railways**（非"iotaDevelopment"，该说法有误）[1][2] | **MrJulsen**（个人，同 DragonLib 作者）[3][4] |
| 仓库 | Creators-of-Create/Create | Layers-of-Railways/Railway | MisterJulsen/Create-Train-Navigator |
| License | MIT | LGPL-3.0 | GPLv3 |
| 1.21.1 NeoForge | ✅ 6.0.10（2026-04-21）[5] | ⚠️ **仅 0.1.0 早期预览，未正式发布**，无公开 1.21 分支 [1][6] | ✅ **beta-0.9.0-C6 已发布**（2026-04-30）[3] |
| 1.20.1 | 0.5.1.j | ✅ 1.7.2 (C6) 正式版 [1] | ✅ 0.9.0-C6 [3] |
| 依赖关系 | — | 依赖 Create | 依赖 Create + DragonLib（≥0.9.0）；**不依赖 SnR** [3][4] |
| 与彼此关系 | — | 互补独立，常一起用，无官方协作 [1][3] | 同左 |

**关键结论**：
- **CRN 已有 1.21.1 NeoForge 版本**，与你们同版本同加载器同 Create 大版本，可直接同实例共存，**不需要跨版本联动**。你们此前担心"CRN 只有 1.20.1 要不要跨版本"——这个担忧不成立。
- **SnR 1.21.1 尚不可靠**：仅 0.1.0 早期预览在测试者间流通，issue #734 日志显示该预览存在"train and bogeys not loading"问题 [6]。若目标是稳定 1.21.1，**当前不建议把 SnR 作为功能前提**，但可设计成"检测到 SnR 存在则额外读取其转向架/车厢信息"的软增强。
- CRN 与 SnR **无硬耦合**，二者独立维护，可分别按存在性检测做可选集成。

---

## 3. 数据来源分析：Create 原生 API 能提供什么

访问入口：服务端 `Create.RAILWAYS`（`com.simibubi.create.content.trains.GlobalRailwayManager`），客户端 `CreateClient.RAILWAYS` [10][11]。

### 3.1 服务端可读（完整数据，推荐读取侧）

`GlobalRailwayManager` 的 `public` 字段 [10]：
- `Map<UUID, Train> trains` — 列车主注册表
- `Map<UUID, TrackGraph> trackNetworks` — 所有轨道网络图
- `Map<UUID, SignalEdgeGroup> signalEdgeGroups` — 信号边组

**`Train` 关键字段**（均为 public）[11]：

| 字段 | 类型 | 调度图用途 |
|---|---|---|
| `id` | `UUID` | 列车主键（挂货运/客运配置的锚点） |
| `name` | `Component` | 列车名 |
| `icon` / `mapColorIndex` | — | 图标/颜色 |
| `speed` / `targetSpeed` / `throttle` | `double` | 速度 |
| `graph` | `TrackGraph` | 当前所在轨道图 |
| `currentStation` | `UUID`（null=行驶中） | **是否停靠 + 停靠哪个站** |
| `navigation` | `Navigation` | 目的地、路径、距离、是否等信号 |
| `runtime` | `ScheduleRuntime` | 时刻表进度、调度阶段、ETA 预测 |
| `carriages` | `List<Carriage>` | 编组 |
| `manualTick` / `manualSteer` | — | **区分手动驾驶** |
| `derailed` / `invalid` | `boolean` | 异常状态 |
| `occupiedSignalBlocks` / `reservedSignalBlocks` | — | 信号占用（画"列车在哪段轨道"） |

**`ScheduleRuntime` 关键字段**[12]：
- `Schedule schedule` — 当前时刻表（`List<ScheduleEntry>`，每条 = `instruction` + 多列 `conditions`）
- `int currentEntry` — **当前执行到第几站**
- `State state` — `PRE_TRANSIT` / `IN_TRANSIT` / `POST_TRANSIT`
- `boolean isAutoSchedule` — **区分自动调度 vs 玩家手持时刻表**
- `List<Integer> predictionTicks` + `submitPredictions()` → `Collection<TrainDeparturePrediction>` — **每站累积 ETA**
- `boolean paused` / `completed`

**`GlobalStation`**（站点，`TrackGraph` 上的 `EdgePointType.STATION`）[13]：
- `name`（String）、`blockEntityPos`（BlockPos）、`getPresentTrain()`（正停靠的列车）、`getImminentTrain()`（30 格内即将到达）、`getNearestTrain()`

**`TrackGraph` / `TrackNode` / `TrackEdge`**（轨道拓扑）[14]：
- `TrackGraph` 持有节点与边集合；`locateNode(TrackNodeLocation)` 定位节点
- `TrackNode` 顶点，`getLocation()` 含 `dimension`（跨维度）
- `TrackEdge` 边，承载 `EdgeData`（站点/信号/观察器等轨道点）
- `EdgePointType` 注册三种：`STATION` / `SIGNAL` / `OBSERVER` [15]

**车厢货物**：通过 `carriage.storage` 的 `getAllItems()`（`IItemHandlerModifiable`）与 `getFluids()`（`IFluidHandler`）访问，统一聚合，**无独立货运/客运车厢类** [11][13]。

### 3.2 客户端可读（受限）

`Train.STREAM_CODEC` 只同步核心静态字段（id/name/carriages/icon 等），**不含** speed/runtime/navigation/currentStation [11]。客户端仅能拿到：
- `GlobalTrainDisplayData` 的发车预测（Create 原生显示屏用）
- 轨道图经 `TrackGraphSync` 同步的拓扑

**结论：调度图数据必须在服务端读取，再经你们已有的 HTTP/WebSocket 推到前端。** 这与现有 `WebMirror`（服务端镜像）模式一致，无需新架构。

### 3.3 事件机制（重要约束）

**Create 没有为列车生命周期暴露 NeoForge Event**（创建/销毁/到站/离站）[10][11]。第三方集成只能：
- **Mixin** 拦截 `Train.arriveAt` / `GlobalStation.trainDeparted` / `GlobalRailwayManager.addTrain/removeTrain`
- **轮询** `Create.RAILWAYS.trains`（你们现有 `WebMirror` 已是 10s 心跳轮询模式，可复用）

---

## 4. 集成方案对比

| 方案 | 数据源 | 优点 | 缺点 |
|---|---|---|---|
| **A. 纯 Create 原生** | `Create.RAILWAYS` | 零额外依赖，数据最全（拓扑+时刻表+ETA+货物） | 无线路/分类/站点标签元数据；无结构化到发事件 |
| **B. Create + CRN 可选** | Create + CRN 事件/工具类 | 额外拿到 `TrainLine`（线路+颜色）、`StationTag`（站点分组+站台号）、`TrainCategory`、结构化 `TrainStop`/到发事件 | CRN 事件类属内部 API，无稳定性保证，需版本兼容封装 |
| **C. 纯文本镜像**（现有） | DisplaySource `provideText` | 零改造 | **对 CRN `advanced_display` 源完全失效**（provideText 返回 EMPTY）[8]；对 Create 原生源只能拿到文本行，丢失结构 |

**推荐方案 B**：以 Create 原生 API 为主干（拓扑、列车、时刻表、ETA、货物都来自这里），CRN 作为可选增强（线路配色、站点标签、到发事件、路径搜索结果）。CRN 设为 optional 依赖，`ModList.isLoaded("createrailwaysnavigator")` 守卫，缺失时降级为纯 Create 数据。

### 4.1 CRN 集成入口（方案 B 细节）

CRN 的"半公开"事件总线 `de.mrjulsen.crn.event.CRNEventsManager`[16]：
- `getEvent(Class)` / `getEventOptional(Class)` 取事件实例
- `IEvent.register(String modid, T callback)` / `unregister(modid)` 订阅

可订阅事件（`de.mrjulsen.crn.event.events`）[17]：
- `SubmitTrainPredictionsEvent`：`(Train, Collection<TrainDeparturePrediction>, entryCount, accumulatedTime, current)` — **结构化预测数据**
- `TrainArrivalAndDepartureEvent`：`(Train, Optional<GlobalStation>, boolean arrival)` — **实时到/发**
- `GlobalTrainDisplayDataRefreshEventPre/Post`、`TrainDestinationChangedEvent`、`ScheduleResetEvent`、`TotalDurationTimeChangedEvent`、`DefaultTrainDataRefreshEvent`

直接调用工具类 [9]：
- `TrainUtils.getDeparturesAtStationName(stationName, ...)` → `List<TrainStop>`（站点到发预测）

CRN 数据模型（`de.mrjulsen.crn.data`）[18]：
- `TrainLine`（名称+颜色）、`TrainCategory`、`StationTag`（站点分组+站台号）、`TrainConnection`、`TrainInfo`
- `NavigableGraph` + `Node` + `EdgeData`（CRN 自己的导航图，含换乘 `TransferConnection`）
- `GlobalSettings`（服务端单例，22KB，存线路/分类/标签/黑名单）

**风险**：CRN 事件类**不在 `api` 包**，属内部类，公开可调但无稳定性保证，跨版本可能改名/移除 [16][17]。需做版本兼容封装 + 反射兜底。

---

## 5. 调度图设计要素与数据缺口

目标视图：网页端调度图，含铁道、站点、在线列车、列车信息、时刻表。

### 5.1 数据映射

| 调度图要素 | 数据来源 | 可得性 |
|---|---|---|
| 轨道拓扑（线段连接） | `TrackGraph` 的 `TrackNode`/`TrackEdge` | ✅ 服务端完整；客户端经 `TrackGraphSync` 同步（可读） |
| 站点（位置+名称） | `GlobalStation`（`name` + `blockEntityPos`） | ✅ |
| 在线列车（位置） | `Train` + `carriage.leadingBogey().getLeadingPoint()`（`TravellingPoint` 在轨道图上的位置） | ✅ 服务端；需自行将 TravellingPoint 转世界坐标 |
| 列车当前状态 | `currentStation`（停靠）/ `navigation`（行驶中/等信号）/ `derailed` | ✅ |
| 时刻表（自动调度） | `train.runtime.schedule.entries` + `currentEntry` + `state` | ✅ 仅当 `runtime.schedule != null` |
| 时刻表 ETA | `runtime.submitPredictions()` → `TrainDeparturePrediction` | ✅ |
| 货运/客运属性 | **Create 无原生字段** | ❌ 需自建持久化 |
| 线路配色/站点标签 | CRN `TrainLine` / `StationTag` | ⚠️ 可选，CRN 存在时才有 |
| 列车图标 | `Train.icon`（`TrainIconType`） | ✅ 需自行渲染或回退默认图标 |

### 5.2 拓扑绘制的关键难点

1. **轨道图是世界坐标的图，不是抽象线路图**：`TrackNode`/`TrackEdge` 带真实 `BlockPos`。要画成"地铁路线图"风格的抽象调度图，需做**图简化/去弯**——保留站点节点、合并中间转向节点。这是前端渲染工程，数据层只需把节点+边+站点位置推过去。
2. **跨维度**：`TrackNodeLocation` 含 `dimension` 字段，调度图需按维度分页或分图层 [14]。
3. **列车实时位置**：`TravellingPoint` 表示列车在轨道边上的插值位置，需转成世界坐标才能在图上定位。若简化为"列车当前在哪两个站点之间"，可直接用 `navigation.currentPath` 的起止站点，无需精确插值——这对调度图足够。

### 5.3 调度甘特图（时刻表时间轴）

除拓扑图外，可加一个**时间轴视图**：X 轴=时间，Y 轴=列车，每条横杠=该列车按时刻表在各站的到发时段。数据来自 `runtime.schedule.entries`（站点顺序）+ `predictionTicks`（ETA）。这比拓扑图更接近"调度图"本义，且数据完全可得。CRN 的 `TrainStop`/`StationDisplayData` 可补充实际到发历史 [9][18]。

---

## 6. 货运/客运配置方案

Create 原生不区分货运/客运车厢（统一 `Carriage.storage`）[11]。要实现"配置这列火车是货运/客运"，需自建持久化层，模式与现有 `NetworkStorage`（`config/webboard-networks.json`）一致：

- 新建 `TrainMetadataStorage`，持久化到 `config/webboard-trains.json`
- 结构：`Map<UUID, TrainMeta>`，`TrainMeta = { type: "freight"|"passenger"|"mixed", label, color, notes }`
- 锚点用 `Train.id`（UUID，跨重启稳定，Create 持久化在 `RailwaySavedData`）
- CRUD API：`GET/PUT/DELETE /api/trains/{id}/meta`
- 列车销毁时（`invalid==true` 或从 `trains` 消失）清理对应 meta（或保留标记 removed，与 BoardDatabase 一致）

可选增强：自动推断初始类型——遍历 `carriage.storage.getAllItems()` 是否非空 + 是否有乘客实体，给个默认建议值，用户可改。

---

## 7. 推荐架构

```
┌─────────────────────────────────────────────────────────────┐
│ 服务端（Minecraft 主线程 / WebMirror 轮询线程）              │
│                                                              │
│  TrainMirror (新增)                                          │
│   ├─ 每 N 秒遍历 Create.RAILWAYS.trains                      │
│   ├─ 提取: id/name/speed/currentStation/runtime/ETA/cargoes  │
│   ├─ 提取拓扑: trackNetworks → nodes/edges/stations          │
│   ├─ [可选] CRNEventsManager 订阅到发事件 (实时推送)          │
│   ├─ [可选] 读 CRN GlobalSettings 拿 TrainLine/StationTag     │
│   └─ 推 BoardRegistry → WebSocketHub → 前端                  │
│                                                              │
│  TrainMetadataStorage (新增, config/webboard-trains.json)    │
│   └─ 货运/客运/混合 + 标签 + 颜色, 锚 Train.id(UUID)         │
│                                                              │
│  /api/trains, /api/trains/{id}/meta, /api/track-graph        │
│  (REST + WS push, 复用现有 HttpServer/WebSocketHub)          │
└─────────────────────────────────────────────────────────────┘
                              │ WebSocket + REST
┌─────────────────────────────────────────────────────────────┐
│ 前端（现有 vanilla JS 看板，新增第三视图）                    │
│                                                              │
│  视图切换: 看板 / 应力网 / 火车调度                          │
│                                                              │
│  火车调度视图                                                │
│   ├─ 拓扑图 (SVG): 节点=站点, 边=轨道, 列车点=在线列车        │
│   ├─ 时刻表甘特图 (SVG): 时间轴 × 列车, 横杠=到发时段        │
│   ├─ 列车详情面板: 状态/速度/编组/货物/时刻表进度/ETA        │
│   └─ 列车配置: 货运/客运/混合 + 标签 (调 /api/trains/{id})   │
└─────────────────────────────────────────────────────────────┘
```

**与现有架构的契合点**：
- 服务端镜像复用 `WebMirror` 的心跳轮询模式（无需 Create 事件，降低耦合）
- HTTP/WebSocket 复用现有 `HttpServer` + `WebSocketHub`
- 持久化复用 `NetworkStorage` 的"立即原子写"模式（火车元数据改动低频）
- 前端复用现有 SVG 折线图基础设施（`renderChartSvg`）扩展为拓扑图/甘特图
- CRN 软依赖模式复用 `IconUploadService` 里反射调 Iris 的思路（`ModList.isLoaded` + 反射 + ClassNotFoundException 兜底）

---

## 8. 风险与未决问题

1. **CRN 内部 API 不稳定**：事件类不在 `api` 包，跨版本可能改名 [16][17]。缓解：版本探测 + 反射兜底 + 缺失时降级。
2. **SnR 1.21.1 不可用**：当前仅 0.1.0 预览，无法作为功能前提 [1][6]。缓解：SnR 集成推迟到其正式发布；当前调度图数据不依赖 SnR（Create 原生已足够）。
3. **无 Create 事件**：列车到站/离站需轮询或 Mixin [10][11]。轮询延迟（现有 10s 心跳）对调度图可能偏慢，可针对火车场景缩短到 2-3s，或用 Mixin 做实时事件桥接。
4. **拓扑图简化算法**：世界坐标轨道图转抽象线路图需图简化，工程量中等。可先做"按维度分组的站点连线图"（只画站点+连接关系，不画精确轨道），后续再优化。
5. **列车位置精度**：`TravellingPoint` 转世界坐标较复杂；先用"当前站/下一站"粗粒度定位，足以满足调度图需求。
6. **现有 SourceLabels 错误**：`createrailwaysnavigator:train_display` 应改为 `createrailwaysnavigator:advanced_display`，且需意识到该源文本镜像抓不到内容 [7][8]。
7. **GPLv3 传染性**：CRN 是 GPLv3 [3]。若把 CRN 作为编译期依赖（引用其类），需评估许可证影响。**纯运行时软依赖 + 反射调用**通常可规避（不静态链接其代码），但建议法务确认。替代：完全不用 CRN 类，只用 Create 原生数据 + 自建线路/标签元数据。

---

## 9. 下一步建议（按优先级）

1. **P0 修正现存 bug**：改 `SourceLabels.java` 的 CRN 源 id；在文档/代码注释标注该源文本镜像无效。这是独立小修，不阻塞调度图。
2. **P1 阶段一（纯 Create，不依赖 CRN/SnR）**：
   - 服务端 `TrainMirror`：轮询 `Create.RAILWAYS`，提取列车+站点+拓扑+时刻表+ETA
   - REST `/api/trains`、`/api/track-graph`，WS 推送列车状态变化
   - `TrainMetadataStorage` + `/api/trains/{id}/meta`（货运/客运配置）
   - 前端火车调度视图：拓扑图（站点+连接+列车点）+ 列车详情面板 + 配置面板
   - 时刻表甘特图（用 `runtime.schedule` + `predictionTicks`）
3. **P2 阶段二（CRN 可选增强）**：
   - 软依赖 CRN，订阅 `SubmitTrainPredictionsEvent` / `TrainArrivalAndDepartureEvent`
   - 读 `GlobalSettings` 拿 `TrainLine`（线路配色）/ `StationTag`（站台号）
   - 前端用 CRN 线路配色着色拓扑图，用站台号标注站点
4. **P3 阶段三（SnR，待其 1.21.1 正式版）**：
   - 软依赖 SnR，读其转向架/车厢类型，丰富列车详情
   - 这阶段暂不启动，持续关注 Layers-of-Railways/Railway 仓库 1.21 进展

阶段一即可交付一个完整可用的调度图，CRN/SnR 都是锦上添花。

---

## Sources

[1] CurseForge - Create: Steam 'n' Rails (Project ID 688231): https://www.curseforge.com/minecraft/mc-mods/create-steam-n-rails
[2] GitHub - Layers-of-Railways/Railway: https://github.com/Layers-of-Railways/Railway
[3] CurseForge - Create Railways Navigator (Project ID 935929): https://www.curseforge.com/minecraft/mc-mods/create-railways-navigator
[4] GitHub - MisterJulsen/Create-Train-Navigator: https://github.com/MisterJulsen/Create-Train-Navigator
[5] Create 6.0.10 官方 changelog: https://wiki.createmod.net/users/changelogs/6.0.10
[6] Issue #734（SnR 1.21.1 0.1.0 预览日志，train and bogeys not loading）: https://github.com/Layers-of-Railways/Railway/issues/734
[7] 源码 ModExtras.java（CRN 注册 DisplaySource "advanced_display"）: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/registry/ModExtras.java
[8] 源码 AdvancedDisplaySource.java（provideText 返回 EMPTY）: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/block/display/AdvancedDisplaySource.java
[9] 源码 AdvancedDisplayTarget.java（数据流/TrainUtils/StationDisplayData）: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/block/display/AdvancedDisplayTarget.java
[10] 源码 GlobalRailwayManager.java: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/GlobalRailwayManager.java
[11] 源码 Train.java: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/entity/Train.java
[12] 源码 ScheduleRuntime.java: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/schedule/ScheduleRuntime.java
[13] 源码 GlobalStation.java: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/station/GlobalStation.java
[14] 源码 graph 子包（TrackGraph/TrackNode/TrackEdge/TrackGraphSync）: https://github.com/Creators-of-Create/Create/tree/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph
[15] 源码 EdgePointType.java（STATION/SIGNAL/OBSERVER）: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/EdgePointType.java
[16] 源码 CRNEventsManager.java + IEvent.java: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/event/CRNEventsManager.java
[17] 源码 SubmitTrainPredictionsEvent.java / TrainArrivalAndDepartureEvent.java: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/event/events/SubmitTrainPredictionsEvent.java
[18] 源码 CRN data 包文件树（NavigableGraph/GlobalSettings/TrainLine/StationTag/TrainStop 等）: https://api.github.com/repos/MisterJulsen/Create-Train-Navigator/git/trees/d59b71b83c174759a7062c621990dfb355867d99?recursive=1
