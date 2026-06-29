# PRD：火车调度图（Train Dispatch Map）

> 版本：v0.1（草案）
> 日期：2026-06-28
> 状态：待评审
> 关联文档：
> - [train-integration-feasibility.md](./train-integration-feasibility.md)（第一轮可行性调研）
> - [train-integration-implementation-analysis.md](./train-integration-implementation-analysis.md)（第二轮实施分析）
> - [web-map-engine-research.md](./web-map-engine-research.md)（Web 地图引擎选型调研）
> - [abstract-layout-algorithm-research.md](./abstract-layout-algorithm-research.md)（抽象线路图算法调研）

---

## 1. 概述

### 1.1 产品定位

在 `create_web_board` 的 Web 看板中新增"火车调度"视图，以**交互式地图**形式展示 Minecraft Create mod 的火车轨道网络、在线列车、站点、时刻表与调度信息，并支持列车分类配置与路径搜索。

### 1.2 核心价值

- **全局可视化**：玩家不再需要在游戏内到处跑动查看轨道，一张图看清全服铁道网
- **调度透明**：每列车的当前位置、目的地、ETA、时刻表进度一目了然
- **分类管理**：复用 CRN 的 TrainCategory 体系，统一管理货运/客运分类，与游戏内 Navigator 共享
- **路径规划**：Web 端发起 A→B 搜索，可视化换乘方案
- **离线自托管**：所有资源打进 mod jar，不依赖外网 CDN

### 1.3 用户故事

| 角色 | 故事 |
|---|---|
| 服主 | 我想在网页上看到全服铁道网，了解哪些站连哪些线 |
| 调度员 | 我想实时看到每列车在哪、去哪、什么时候到 |
| 货运玩家 | 我想给货运列车统一打标签，让客运导航自动排除它们 |
| 乘客 | 我想在 Web 上查 A 站到 B 站怎么坐车、要多久、要不要换乘 |
| 新玩家 | 我想看一张像地铁线路图那样的抽象图，快速理解网络结构 |

---

## 2. 范围

### 2.1 In Scope（本次交付）

- **三种地图视图**（可切换）：
  1. 真实坐标视图（按 Minecraft 世界坐标画轨道走向）
  2. 抽象线路图视图（地铁站风格，正交化布局）
  3. 路径高亮视图（叠加层，A→B 路线高亮）
- **多维度分页**：overworld / nether / end 三个维度独立分页
- **在线列车**：实时显示列车位置、状态、目的地、ETA
- **站点交互**：点击站点显示详情面板（即将到达列车、历史到发、站台号）
- **列车详情**：点击列车显示详情面板（编组、货物、时刻表、当前 category/line）
- **列车分类 CRUD**：创建/编辑/删除 TrainCategory（透传 CRN GlobalSettings）
- **路径搜索**：选起点终点，显示换乘方案，在地图上高亮
- **时刻表甘特图**：时间轴 × 列车的到发时段视图

### 2.2 Out of Scope（本次不做）

- 游戏内 GUI（Web 看板专用，不做游戏内 HUD）
- 列车手动操控（只读+配置，不发操控指令）
- 轨道编辑（只读拓扑，不修改 Create 轨道图）
- SnR（Steam 'n' Rails）集成（1.21.1 不可用，暂不考虑）
- 历史回放（只做实时，不做时间轴回放）

### 2.3 依赖

| 依赖 | 类型 | 说明 |
|---|---|---|
| Create 6.0.10 | 必需 | 火车系统数据源 |
| Create Railways Navigator (CRN) beta-0.9.0-C6 | 可选（软依赖） | 分类/线路/站点标签/路径搜索/历史到发；缺失时降级为纯 Create 数据 |
| Leaflet 1.9.4 | 内嵌（自托管） | Web 地图引擎，~50KB gzipped，打进 mod jar |

---

## 3. 功能需求

### 3.1 视图切换

**需求**：用户可在三种地图视图间自由切换，切换时保持当前维度、缩放、中心点。

| 视图 | 说明 | 默认 |
|---|---|---|
| 真实坐标 | 按 BlockPos 画轨道走向，站点位置真实，曲线真实 | ✅ |
| 抽象线路图 | 地铁站风格，站点正交化排列，只保留拓扑 | |
| 路径高亮 | 叠加层，在当前视图上高亮搜索结果路径 | 独立叠加层 |

**切换控件**：地图右上角的图层切换器（Leaflet `L.control.layers`）+ 顶部工具栏的视图模式按钮。

**切换行为**：
- 真实 ↔ 抽象：两套独立的 `L.layerGroup`，切换时 `removeLayer(old); addLayer(new)`，并 `fitBounds` 适配新视图坐标范围
- 路径高亮：独立 `L.layerGroup`，可叠加在任一基础视图上

### 3.2 真实坐标视图

**数据**：
- 节点（TrackNode）：`node.getLocation().getLocation()` → 世界坐标 Vec3
- 边（TrackEdge）：`graph.getConnectionsFrom(node)`，曲线用 `edge.getPosition(graph, t)` 采样
- 站点（GlobalStation）：`graph.getPoints(EdgePointType.STATION)`，世界坐标同上

**渲染**：
- 轨道边：`L.polyline`，按 `TrackMaterial` 着色（或统一色 + 站点高亮）
- 站点：`L.marker`，圆形 divIcon，显示站名
- 跨维度边：虚线标注（`interDimensional == true`）

**坐标系映射**：MC `(x, z)` → Leaflet `[z, x]`（z 向南=屏幕向下，方向天然一致）

### 3.3 抽象线路图视图

**布局算法**：手写"力导向 + 八方向正交化后处理"（Hong Method 4 简化版），~200 行 JS，零依赖。

**算法步骤**：
1. 图简化：缩并非站点的 degree-2 节点（保留站点级拓扑）
2. 初始化位置：用世界坐标做初值（保留地理方位感），或缓存的上次布局
3. 力导向迭代（Fruchterman-Reingold 简化版，300 次）
4. 八方向正交化后处理（磁弹簧 + 网格吸附，50 次）
5. 还原被缩并的中间节点（沿线段均匀分布）

**动态稳定性**：
- 缓存上次布局作为下次初值（mental map preservation）
- 新站点从邻接点平均位置出生
- 站点增减时只局部微调，不整体跳变

**渲染**：
- 轨道边：`L.polyline`，按 `TrainLine` 颜色着色；多 line 共边时横向偏移 5px
- 站点：`L.divIcon`，圆形或方块，显示站名 + 站台号（来自 StationTag）

### 3.4 多维度分页

**需求**：overworld / nether / end 三个维度独立分页，切换时重建图层。

**实现**：
- 顶部 tab 切换维度
- 每个维度一个 `L.layerGroup`（真实坐标）或一份布局缓存（抽象图）
- 切换时 `map.removeLayer(old); map.addLayer(new); map.fitBounds(...)`
- 跨维度边在两端维度都显示（虚线 + 标注对端维度名）

### 3.5 在线列车

**数据轮询**：
- 频率：0.5s（10 tick，匹配 Create 官方 TrainMapSync 节奏）
- 服务端主线程读：`train.carriages.get(i).getLeadingPoint().getPosition(train.graph)`
- 字段：id / name / 位置 / speed / currentStation / navigation.destination / navigation.distanceToDestination / runtime.state / runtime.currentEntry / derailed / occupiedSignalBlocks

**渲染**：
- 列车图标：`L.divIcon`，按 TrainIconType 或 category 着色
- 位置更新：`marker.setLatLng([z, x])`
- 状态标注：停靠中（图标变绿）/ 行驶中（图标箭头朝向）/ 等信号（黄色边框）/ 脱轨（红色 X）

**交互**：
- 点击列车：弹出详情面板
- 悬停：tooltip 显示车名 + 当前站 → 目标站 + ETA

### 3.6 站点详情面板

**触发**：点击站点 marker。

**内容**：
- 站点名 + 站台号（来自 StationTag）
- 当前停靠列车（`station.getPresentTrain()`）
- 即将到达列车列表（`station.getImminentTrain()` + CRN `TrainUtils.getDeparturesAtStationName()`）：
  - 车名 / 线路 / 预计到达时间 / 状态（BEFORE/ANNOUNCED/STAYING/AFTER）
- 历史到发（CRN `DepartureHistory.getDeparturesAtStation()`）：
  - 最近 N 次到发记录，按 category/line 分组统计

### 3.7 列车详情面板

**触发**：点击列车 marker 或从列表选择。

**内容**：
- 基本信息：车名 / 图标 / 当前 category / 当前 line / 状态
- 运行状态：速度 / 当前站（若停靠）/ 目标站 / 剩余距离 / ETA
- 编组：车厢数量 / 车厢类型（转向架）/ 货物清单（`carriage.storage.getAllItems()`）
- 时刻表：`runtime.schedule.entries` 列表，高亮当前 entry，显示每站 ETA
- 调度阶段：`runtime.state`（PRE_TRANSIT / IN_TRANSIT / POST_TRANSIT）
- 手动/自动：`runtime.isAutoSchedule` / `manualTick`

### 3.8 列车分类 CRUD

**需求**：在 Web 端创建/编辑/删除 TrainCategory，透传 CRN `GlobalSettings`。

**CRUD 入口**：配置面板的"列车分类"标签页。

**操作**：
- 列表：`GlobalSettings.getInstance().getAllTrainCategories()`
- 创建：`createOrGetTrainCategory(name, owner)` → 弹窗输入 name + 选 color
- 编辑：`setName()` / `setColor()` → 行内编辑
- 删除：`removeTrainCategory(uuid)` → 确认弹窗（提示会解除所有列车关联）

**约束**：
- name 最大 32 字符（CRN 限制）
- 同名视为相等（CRN equals/hashCode 仅基于 name）
- 颜色用 `DLColor`（CRN 依赖的 DragonLib 颜色类型）

**给列车设置 category**：
- 不在 Web 端直接改列车的 category（那需要改列车的 Schedule NBT，侵入太深）
- 列车的 category 由玩家在游戏内通过 `TravelSectionInstruction` 设置
- Web 端只读显示每列车当前的 category/line

### 3.9 路径搜索

**需求**：用户选起点终点，显示换乘方案，在地图上高亮。

**入口**：顶部工具栏"路径搜索"按钮 → 弹窗选起点终点。

**搜索**：
- 调用 CRN `NavigableGraph.searchRoutes(start, dest, playerId, false)`（**后台线程**，建图开销大）
- 返回 `List<Route>`，按换乘数升序、发车时间升序排列

**结果展示**：
- 方案列表：总时长 / 换乘次数 / 出发时间 / 到达时间
- 选中方案：在地图上高亮路径（`L.polyline` 加粗 + 改色）
- 换乘点：特殊 marker 标注
- 途经站点：按顺序列出

**地图高亮**：
- 独立 `path-highlight` layerGroup
- 高亮 polyline 放在 `map.createPane('highlight', 600)` 单独 pane，z-index 高于轨道
- 清除：`layerGroup.clearLayers()`

### 3.10 时刻表甘特图

**需求**：时间轴 × 列车的到发时段视图，辅助调度决策。

**布局**：
- X 轴：时间（未来 2 小时窗口，可滚动）
- Y 轴：列车列表
- 每条横杠：该列车按时刻表在各站的到发时段

**数据**：
- `train.runtime.schedule.entries`（站点顺序）
- `runtime.predictionTicks` / `submitPredictions()`（每站累积 ETA）
- CRN `TrainStop`（更精确的到发时间 + 延误）

**交互**：
- 点击横杠：跳转到对应列车的详情面板
- 悬停：tooltip 显示该段的起止站 + 时间

### 3.11 视图导航与交互

| 交互 | 行为 |
|---|---|
| 鼠标滚轮 | 缩放（Leaflet 内置） |
| 拖拽 | 平移（Leaflet 内置） |
| 双指 | 触摸缩放（Leaflet 内置） |
| 点击站点 | 站点详情面板 |
| 点击列车 | 列车详情面板 |
| 悬停 | tooltip |
| 图层切换器 | 真实/抽象/路径高亮 |
| 维度 tab | 切换 overworld/nether/end |
| 工具栏 | 路径搜索 / 分类管理 / 甘特图 |

---

## 4. 数据架构

### 4.1 服务端数据流

```
┌─────────────────────────────────────────────────────────────┐
│ 服务端（Minecraft 主线程 + 后台工作线程）                    │
│                                                              │
│  TrainMirrorService（新增）                                   │
│   ├─ 拓扑轮询（10s 或 Create.RAILWAYS.version 变化触发）     │
│   │   └─ 遍历 Create.RAILWAYS.trackNetworks                  │
│   │      → nodes/edges/stations 序列化为 JSON                │
│   ├─ 列车轮询（0.5s，主线程任务队列）                         │
│   │   └─ train.carriages.get(i).getLeadingPoint().getPosition│
│   │      + currentStation / navigation / runtime / cargoes   │
│   ├─ [CRN] 事件订阅                                          │
│   │   └─ TrainArrivalAndDepartureEvent → 标记脏 → 立即推送   │
│   ├─ [CRN] 数据读取                                          │
│   │   └─ TrainUtils.getDeparturesAtStationName()             │
│   │      + GlobalSettings.getAllTrainCategories/Lines/Tags   │
│   │      + TrainListener.getTrainData(id).getCurrentSection  │
│   └─ 推 WebSocketHub → 前端                                  │
│                                                              │
│  持久化                                                       │
│   ├─ 列车分类/线路/站点标签 → CRN GlobalSettings (NBT)       │
│   └─ 用户自定义标签/备注 → config/webboard-trains.json       │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 REST API

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/trains` | GET | 列车列表 + 状态 |
| `/api/trains/{id}` | GET | 列车详情（编组/货物/时刻表） |
| `/api/track-graph?dim={dim}` | GET | 指定维度拓扑（节点+边+站点） |
| `/api/train-categories` | GET/POST/PUT/DELETE | CRN 分类 CRUD（透传 GlobalSettings） |
| `/api/train-lines` | GET/POST/PUT/DELETE | CRN 线路 CRUD |
| `/api/station-tags` | GET/POST/PUT/DELETE | CRN 站点标签 CRUD |
| `/api/routes/search` | POST | A→B 路径搜索（调 CRN NavigableGraph） |
| `/api/departure-history?station={name}` | GET | 站点历史到发（CRN DepartureHistory） |

### 4.3 WebSocket 推送

| 事件 | 频率 | 数据 |
|---|---|---|
| `train:position` | 0.5s | 所有列车的位置 + 状态增量 |
| `train:arrival` | 事件触发 | `{trainId, stationName, arrival: true}` |
| `train:departure` | 事件触发 | `{trainId, stationName, arrival: false}` |
| `track-graph:dirty` | 10s 或 version 变化 | 通知前端重新拉取拓扑 |

---

## 5. 前端架构

### 5.1 文件结构

```
assets/create_web_board/web/
├── index.html                          # 看板入口（增加"火车调度"视图入口）
├── style.css                           # 现有样式 + 火车调度样式
├── app.js                              # 现有入口
├── train/
│   ├── train-app.js                    # 火车调度视图入口 IIFE
│   ├── map-engine.js                   # Leaflet 初始化、CRS.Simple 配置
│   ├── coord.js                        # MC(x,z) <-> latLng 转换
│   ├── tracks.js                       # 轨道 polyline 渲染（真实视图）
│   ├── stations.js                     # 站点 marker 渲染、点击事件
│   ├── trains.js                       # 在线列车 marker + 0.5s 轮询
│   ├── abstract-layout.js              # 抽象线路图布局算法（手写）
│   ├── path-highlight.js               # 路径搜索结果高亮
│   ├── dimension-pager.js              # overworld/nether/end 分页切换
│   ├── detail-panels.js                # 站点/列车详情面板
│   ├── category-manager.js             # 列车分类 CRUD UI
│   ├── route-search.js                 # 路径搜索 UI
│   └── gantt-chart.js                  # 时刻表甘特图
└── lib/
    └── leaflet/                        # 第三方库（自托管）
        ├── leaflet.js                  # ~42 KB gz
        ├── leaflet.css                 # ~4 KB gz
        └── images/                     # ~5 KB
```

### 5.2 地图引擎：Leaflet CRS.Simple

**选型理由**（详见 [web-map-engine-research.md](./web-map-engine-research.md)）：
- vanilla JS 友好（`<script>` 标签即可，UMD 包）
- 离线自托管（~50KB gzipped，4 个文件）
- CRS.Simple 专为非地理方格坐标设计，MC `(x,z)` → `[z,x]` 一行映射
- 内置 pan/zoom/触摸支持
- 图层机制天然支持真实/抽象/路径高亮切换
- 性能足够（数万 marker 55fps）

**不选 MapLibre/OpenLayers**：体积过大（250KB+/150KB+），为地理瓦片设计，本场景用不上 WebGL 吞吐。

**不选纯 SVG 手绘**：几百节点虽在舒适区，但 pan/zoom/触摸需自行实现，工作量大；Leaflet 免费提供。

**现有 SVG 折线图保留**：统计图表（应力网等）继续用 SVG，不迁移 Leaflet。Leaflet 只用于"地图"概念。

### 5.3 抽象线路图算法

**选型**：手写"力导向 + 八方向正交化"（Hong Method 4 简化版），~200 行 JS，零依赖。

**选型理由**（详见 [abstract-layout-algorithm-research.md](./abstract-layout-algorithm-research.md)）：
- 几十节点规模 < 10ms/次布局，完全实时
- 零依赖，符合项目约束
- 可针对 Create 轨道特性定制
- 缓存上次布局作为下次初值，保证动态稳定性

**不选 d3-force**：虽只有 ~9KB，但需要自己写磁弹簧扩展，收益不大反而增加文件依赖。

**不选 elk.js/cola.js**：体积偏大（53KB/80KB），算法不专做地铁图。

**算法参数**（起点值，需实测调）：

| 参数 | 建议值 |
|---|---|
| 力导向迭代次数 | 300 |
| 退火系数 | 0.95 |
| 理想边长 k | 80 px |
| 中心重力 | 0.1 |
| 磁弹簧迭代 | 50 |
| 网格大小 | 40 px |
| 平行线偏移 | 5 px |

---

## 6. 后端架构

### 6.1 新增服务端模块

```
src/main/java/com/example/webboard/content/train/
├── TrainMirrorService.java         # 镜像服务（仿 WebMirror 模式）
├── TrainSnapshot.java              # 列车快照 record
├── TrackGraphSnapshot.java         # 拓扑快照 record
├── TrainMetadataStorage.java       # 用户自定义标签/备注持久化（JSON）
├── CrnBridge.java                  # CRN 软依赖桥（ModList.isLoaded 守卫 + 反射兜底）
└── httpserver/
    ├── TrainRoutes.java            # /api/trains, /api/track-graph
    ├── TrainCategoryRoutes.java    # /api/train-categories (CRUD 透传 CRN)
    ├── TrainLineRoutes.java        # /api/train-lines
    ├── StationTagRoutes.java       # /api/station-tags
    ├── RouteSearchRoutes.java      # /api/routes/search
    └── DepartureHistoryRoutes.java # /api/departure-history
```

### 6.2 线程模型

- **拓扑轮询**：10s 一次，或 `Create.RAILWAYS.version` 变化触发；在服务端主线程读，序列化后交 WS 线程发
- **列车轮询**：0.5s 一次，主线程任务队列调度到 tick 末尾读快照，交 WS 线程发
- **CRN 事件回调**：在服务端主线程触发，只做轻量标记，IO 丢到自己线程
- **路径搜索**：REST 请求线程接收，调度到后台工作线程调 `NavigableGraph.searchRoutes`，结果序列化为 JSON 返回

### 6.3 CRN 软依赖

**编译期**：CRN jar 作为 `compileOnly` 依赖（不打包进我们的 jar）。

**运行期**：
- `ModList.get().isLoaded("createrailways_navigator")` 守卫
- 直接引用 CRN 类（编译期可见）
- ClassNotFoundException 兜底：捕获后降级为纯 Create 数据
- 版本探测：反射读 `CreateRailwaysNavigator.VERSION` 或类似字段，做兼容性判断

**降级策略**：
| 功能 | CRN 存在 | CRN 缺失 |
|---|---|---|
| 列车分类 | 读写 CRN TrainCategory | 隐藏分类功能 |
| 线路配色 | 按 TrainLine 着色 | 统一色 |
| 站台号 | 显示 StationTag.platform | 隐藏 |
| 路径搜索 | 调 CRN NavigableGraph | 隐藏搜索入口 |
| 历史到发 | 读 DepartureHistory | 隐藏历史 |
| 实时到/发事件 | 订阅 CRN 事件 | 退化为 0.5s 轮询判断 |

### 6.4 现有 bug 修复（独立小修）

修正 `SourceLabels.java`：
- `createrailwaysnavigator:train_display` → `createrailwaysnavigator:advanced_display`
- 该源 `provideText()` 返回 EMPTY，文本镜像抓不到；注释说明，调度图不走文本镜像

---

## 7. 实施阶段

### 7.1 阶段一：纯 Create 数据 + 真实坐标图（零 CRN 依赖）

**目标**：可用的真实坐标地图 + 列车位置 + 列车详情。

**后端**：
1. `TrainMirrorService`：拓扑 10s / 列车 0.5s 双频轮询
2. 拓扑提取：遍历 `Create.RAILWAYS.trackNetworks`，序列化 nodes/edges/stations
3. 列车提取：`train.id/name/speed/currentStation/navigation/runtime/cargoes`
4. REST：`/api/trains`、`/api/track-graph`
5. WS：推送列车位置变化

**前端**：
1. Leaflet CRS.Simple 集成（自托管库文件）
2. 真实坐标视图：轨道 polyline + 站点 marker
3. 在线列车 marker + 0.5s 轮询更新
4. 多维度分页 tab
5. 列车详情面板（状态/速度/编组/货物/时刻表）
6. 站点详情面板（当前停靠 + 即将到达，纯 Create 数据）

**交付物**：v0.8.0（待用户确认版本号策略）

### 7.2 阶段二：抽象线路图 + CRN 软依赖

**目标**：地铁站风格抽象图 + CRN 分类/线路/标签/事件。

**后端**：
1. CRN 软依赖桥（`CrnBridge`）
2. 读取 `GlobalSettings`：categories/lines/stationTags
3. 读取 `TrainListener.getTrainData(train.id).getCurrentSection()` 拿每列车当前 category/line
4. 订阅 `TrainArrivalAndDepartureEvent`：实时到/发推 WS
5. REST：`/api/train-categories`、`/api/train-lines`、`/api/station-tags`（CRUD 透传）
6. REST：`/api/departure-history`

**前端**：
1. 抽象线路图布局算法（手写力导向 + 八方向正交化）
2. 抽象视图渲染：按 TrainLine 颜色着色，按 StationTag 显示站台号
3. 视图切换（真实 ↔ 抽象）
4. 实时到/发事件流（WS 推送）
5. 列车分类 CRUD UI
6. 站点详情增强：历史到发

**交付物**：v0.9.0（待用户确认）

### 7.3 阶段三：路径搜索 + 甘特图

**目标**：Web 端路径搜索 + 时刻表甘特图。

**后端**：
1. REST `/api/routes/search`：后台线程调 `NavigableGraph.searchRoutes`
2. 时刻表数据：`runtime.schedule` + `predictionTicks` + CRN `TrainStop`

**前端**：
1. 路径搜索 UI（选起点终点）
2. 路径高亮叠加层
3. 换乘方案列表
4. 时刻表甘特图（SVG，复用现有折线图基础设施）

**交付物**：v0.10.0（待用户确认）

---

## 8. 非功能需求

### 8.1 性能

| 指标 | 目标 |
|---|---|
| 拓扑轮询延迟 | < 50ms（几百节点+边） |
| 列车轮询延迟 | < 10ms（几十列车） |
| 前端地图渲染 | 60fps（几百元素） |
| 抽象布局计算 | < 50ms（几十站点） |
| 路径搜索响应 | < 2s（后台线程） |
| WS 推送频率 | 列车 0.5s / 事件实时 |

### 8.2 兼容性

- Minecraft 1.21.1 NeoForge
- Create 6.0.10+
- CRN beta-0.9.0-C6+（可选）
- 浏览器：Chrome 90+ / Firefox 88+ / Safari 14+（Leaflet 1.9.4 兼容性）

### 8.3 离线自托管

- 所有前端资源打进 mod jar
- 不依赖任何外网 CDN
- Leaflet 库文件内嵌于 `assets/web/lib/leaflet/`
- 总新增体积：~50KB（Leaflet）+ ~30KB（自有 JS/CSS）= ~80KB gzipped

### 8.4 线程安全

- 所有 Create/TrackGraph/Train 读取必须在服务端主线程
- 用主线程任务队列调度到 tick 末尾
- 序列化后交 WS 线程发送，不持有原始对象引用

---

## 9. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| CRN 内部 API 跨版本变动 | 中 | 版本探测 + 反射兜底 + 缺失降级 |
| CRN GPLv3 许可证 | 低 | compileOnly 依赖（不打包进 jar），运行时软依赖；法务确认 |
| Create 无列车生命周期事件 | 低 | 0.5s 轮询足够；可选 Mixin 桥接 |
| TrackGraph 非线程安全 | 中 | 严格主线程读取 + 任务队列 |
| 抽象图布局质量不达标 | 中 | 先实现纯力导向验证，再加八方向后处理；必要时引入 d3-force |
| 路径搜索阻塞主线程 | 中 | 严格后台线程调用 NavigableGraph |
| Leaflet 资源路径在 jar 内映射 | 低 | 验证 NeoForge ResourceLocation 映射；必要时用 `L.Icon.Default.mergeOptions` |
| 列车分类透传 CRN 的并发写 | 中 | GlobalSettings 已用 ConcurrentHashMap；Web CRUD 加同步 |

---

## 10. 验收标准

### 10.1 阶段一验收

- [ ] 打开 Web 看板，可切换到"火车调度"视图
- [ ] 真实坐标图显示轨道走向 + 站点位置
- [ ] 维度 tab 可切换 overworld/nether/end
- [ ] 在线列车实时移动（0.5s 更新）
- [ ] 点击列车显示详情面板（状态/速度/编组/货物）
- [ ] 点击站点显示详情面板（当前停靠 + 即将到达）
- [ ] 无 CRN 时功能正常（降级模式）

### 10.2 阶段二验收

- [ ] 可切换到抽象线路图视图
- [ ] 抽象图按地铁站风格正交化布局
- [ ] 站点增减时布局稳定（不整体跳变）
- [ ] 轨道按 TrainLine 颜色着色
- [ ] 站点显示站台号
- [ ] 可创建/编辑/删除 TrainCategory（透传 CRN）
- [ ] 实时到/发事件推送（WS）
- [ ] 站点详情显示历史到发

### 10.3 阶段三验收

- [ ] 路径搜索 UI 可选起点终点
- [ ] 搜索结果显示换乘方案列表
- [ ] 选中方案在地图上高亮路径
- [ ] 换乘点特殊标注
- [ ] 时刻表甘特图显示时间轴 × 列车
- [ ] 甘特图点击跳转列车详情

---

## 11. 开放问题

1. **版本号策略**：按用户既定策略，未经允许不 bump 版本号。阶段一/二/三分别对应 v0.8.0/v0.9.0/v0.10.0，**待用户确认是否发布及版本号**。
2. **CRN jar 作为 compileOnly 依赖的获取**：需从 CurseForge maven（`curse.maven:create-railways-navigator-935929:<fileId>`）拉取，确认 fileId。
3. **Leaflet 资源在 NeoForge jar 内的路径映射**：需验证 `assets/create_web_board/web/lib/leaflet/leaflet.js` 是否能通过现有 HttpServer 正确暴露为 `/lib/leaflet/leaflet.js`。
4. **抽象图布局算法的调参**：伪代码参数是起点值，需实测调整；可能需要针对 Create 轨道特性（树状多、环路少）做定制优化。
5. **CRN 事件订阅时机**：建议在 `CRNCommonEventsRegistryEvent` 回调里注册，需验证该事件在 mod 加载流程中的触发时机。

---

## 附录 A：关键 API 速查

### Create 原生（服务端 `Create.RAILWAYS`）

```java
// 拓扑
GlobalRailwayManager mgr = Create.RAILWAYS;
Map<UUID, TrackGraph> graphs = mgr.trackNetworks;
for (TrackGraph graph : graphs.values()) {
    for (TrackNodeLocation loc : graph.getNodes()) {
        TrackNode node = graph.locateNode(loc);
        Vec3 worldPos = node.getLocation().getLocation();
        ResourceKey<Level> dim = node.getLocation().getDimension();
        for (Map.Entry<TrackNode, TrackEdge> e : graph.getConnectionsFrom(node).entrySet()) {
            TrackNode n2 = e.getKey(); TrackEdge edge = e.getValue();
            double length = edge.getLength();
            // 曲线采样：edge.getPosition(graph, t) for t in [0,1]
        }
    }
    Collection<GlobalStation> stations = graph.getPoints(EdgePointType.STATION);
}

// 列车
for (Train train : mgr.trains.values()) {
    UUID id = train.id;
    Component name = train.name;
    double speed = train.speed;
    GlobalStation current = train.getCurrentStation();  // null=行驶中
    GlobalStation dest = train.navigation.destination;   // null=未导航
    double distLeft = train.navigation.distanceToDestination;
    ScheduleRuntime runtime = train.runtime;
    List<ScheduleEntry> entries = runtime.schedule.entries;
    int currentEntry = runtime.currentEntry;
    ScheduleRuntime.State state = runtime.state;
    boolean isAuto = runtime.isAutoSchedule;
    boolean manual = train.manualTick;
    boolean derailed = train.derailed;
    // 列车位置
    for (Carriage c : train.carriages) {
        Vec3 pos = c.getLeadingPoint().getPosition(train.graph);
    }
    // 货物
    for (Carriage c : train.carriages) {
        IItemHandlerModifiable items = c.storage.getAllItems();
        IFluidHandler fluids = c.storage.getFluids();
    }
}
```

### CRN（软依赖，`CrnBridge` 守卫）

```java
// 分类/线路/站点标签
GlobalSettings gs = GlobalSettings.getInstance();
ImmutableList<TrainCategory> cats = gs.getAllTrainCategories();
ImmutableList<TrainLine> lines = gs.getAllTrainLines();
List<StationTag> tags = gs.getAllStationTags();

// 列车当前 category/line
Optional<TrainData> td = TrainListener.getTrainData(train.id);
Optional<TrainCategory> cat = td.flatMap(d -> d.getCurrentSection().getTrainCategory());
Optional<TrainLine> line = td.flatMap(d -> d.getCurrentSection().getTrainLine());

// 站点到发预测
List<TrainStop> stops = TrainUtils.getDeparturesAtStationName(stationName, null, true, false);

// 历史到发
Map<String, Data> history = DepartureHistory.getDeparturesAtStation(stationName);

// 路径搜索（后台线程！）
List<Route> routes = NavigableGraph.searchRoutes(startTag, destTag, playerId, false);

// 事件订阅
CRNEventsManager.getEventOptional(TrainArrivalAndDepartureEvent.class)
    .ifPresent(event -> event.register("create_web_board", (train, station, arrival) -> {
        // 标记脏，异步推送
    }));
```

---

## 附录 B：前端关键代码片段

### Leaflet 初始化

```js
var map = L.map('train-map', {
    crs: L.CRS.Simple,
    minZoom: -5,
    maxZoom: 5
});

// MC (x, z) -> Leaflet [z, x]
function mcToLatLng(x, z) { return L.latLng(z, x); }
function latLngToMC(latlng) { return { x: latlng.lng, z: latlng.lat }; }

// 图层
var realLayer = L.layerGroup().addTo(map);
var abstractLayer = L.layerGroup();
var highlightLayer = L.layerGroup();

L.control.layers(null, {
    "真实坐标": realLayer,
    "抽象线路": abstractLayer
}, { collapsed: false }).addTo(map);

// 维度 tab
var dimensionLayers = { overworld: realLayer, nether: L.layerGroup(), end: L.layerGroup() };
```

### 抽象布局算法骨架

```js
function computeAbstractLayout(graph, cachedLayout) {
    // 1. 图简化：缩并非站点 degree-2 节点
    var simplified = contractNonStationDeg2(graph);
    // 2. 初始化位置
    var pos = cachedLayout
        ? reuseOldPositions(simplified, cachedLayout)
        : initFromWorldCoords(simplified);
    // 3. 力导向迭代（Fruchterman-Reingold）
    pos = forceDirected(simplified, pos, { iterations: 300, k: 80, cooling: 0.95 });
    // 4. 八方向正交化 + 网格吸附
    pos = octilinearize(simplified, pos, { iterations: 50, gridSize: 40 });
    // 5. 还原被缩并节点
    return expandContractedNodes(simplified, pos, graph);
}
```

---

## 变更历史

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-06-28 | v0.1 | 初稿 |
