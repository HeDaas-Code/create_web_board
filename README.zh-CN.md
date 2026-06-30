# Create Web Board

[English](README.md) · [简体中文](README.zh-CN.md)

一个面向 **Minecraft 1.21.1 · NeoForge** 的 **Create 6.0.10** 扩展模组。为每个「显示链路
（Display Link）」增加一个「Web」开关，把**任意** Create 显示源的输出实时镜像到本地浏览器
仪表盘——无需自定义显示源，所有原生显示源开箱即用。

当前版本：**0.7.1** · 许可证：**MIT** · 同步方式：**服务端必需，客户端可选**。

## 功能简介

1. 在任意位置放置一个显示链路（来自 Create），选择任意显示源（时刻、应力、锅炉、列车
   状态、物品吞吐……）。
2. 右键打开链路界面 → 翻转新增的 **Web: 开/关** 按钮。
3. 在同一台机器上用任意浏览器打开 `http://localhost:8080/`。
4. 在侧栏实时查看所有已开启的链路，构建聚合产/耗/存的**应力网络**，浏览带趋势图的历史
   记录，并为看板打标签/注释。

本模组**不会**注册自己的 DisplaySource，而是通过 Mixin 包裹 `DisplaySource#transferData`，
镜像底层显示源原本就会产出的内容。开关仅是链路 `sourceConfig` 上的一个 NBT 布尔值
（`WebSynced`），通过 Create 自身的配置包同步——无需自定义网络通信。

自 **0.7.1** 起，仪表盘还内置了**列车调度图**（`/trains.html`），实时镜像 Create 的铁路
网络——手绘 SVG 地图上的实时列车位置、路径搜索、到发记录。当安装了
[Create Railways Navigator](https://www.curseforge.com/minecraft/mc-mods/create-railways-navigator)
（CRN）时，分类 / 线路 / 站点标签会以只读方式从 CRN 同步，路径搜索也会把站点标签展开为
其背后的多个 Create 车站。

## 玩家指南

### 安装

1. 安装 Minecraft 1.21.1 + [NeoForge 21.1.219](https://neoforged.net/)。
2. 安装 [Create 6.0.10](https://www.curseforge.com/minecraft/mc-mods/create) 与
   [Ponder 1.0.82](https://www.curseforge.com/minecraft/mc-mods/ponder)。
3. （可选，推荐用于列车地图）安装
   [Create Railways Navigator 0.9.0+](https://www.curseforge.com/minecraft/mc-mods/create-railways-navigator)
   ——启用从 CRN 同步的分类 / 线路 / 站点标签，以及基于标签的路径搜索。
4. 将 `create_web_board-1.21.1-*.jar` 放入 `mods/` 文件夹。
5. 启动游戏。

**服务端 vs 客户端**：本模组**服务端必需，客户端可选**。专用服务端必须安装；未安装的客户端
仍可加入（链路界面里只是不会出现开关按钮）。在单人或联机主机模式下，客户端 jar 还会渲染
物品图标并上传到仪表盘（见下文「图标渲染」）。

### 使用

1. 合成一个显示链路，放置，选择任意 Create 显示源。
2. 右键 → 翻转 **Web: 开**。
3. 访问 `http://localhost:8080/`。

### 仪表盘功能

- **看板视图** ——每个显示链路一张卡片。显示有效名称、在线/离线徽章、显示源类型标签
  （已翻译为中文，如 `应力` / `锅炉状态` / `列车状态`）、最后更新时间、标签、关联产品图标、
  实时文本行。链路被破坏或停止上报约 5 秒后卡片变灰。
- **网络视图** ——把多个看板组合成统一的**应力网络**。每个成员分配一个角色
  （`producer` 生产 / `consumer` 消耗 / `storage` 存储）和一个 `lineIndex`，告诉仪表盘从
  看板输出的哪一行提取数值。网络卡片显示实时聚合：总产量、总消耗、盈余（= 产量 − 消耗）、
  总存量，下方附成员明细。
- **搜索与分组** ——按名称/显示源/成员过滤；按标签分组看板。
- **看板详情弹窗** ——重命名、编辑标签、选择关联产品物品、查看原始文本，并浏览带手绘 SVG
  趋势图的**历史时间线**。
- **网络详情弹窗** ——网络名称、实时聚合统计、按时间戳聚合每个成员历史的趋势图
  （产量=绿，消耗=橙，盈余=蓝），以及成员表。
- **网络编辑器** ——添加/移除看板，为每个成员设置角色/标签/lineIndex。
- **增强趋势图** ——每张图都包含**均值线**、**变化率标注**（窗口内每秒增量），以及**异常点
  标记**（样本数 ≥6 时，标出偏离均值超过 2σ 的点）。纯 SVG，无 JS 图表库，离线可用。
- **实时** ——每次变更 WebSocket 推送，外加 5 秒 REST 轮询回退与指数退避重连。
- **列车调度图**（`/trains.html`，0.7.1 新增） ——单独页面，实时镜像 Create 铁路网络。单页
  布局，包含手绘 SVG 地图与可折叠侧栏：
  - **地图面板** ——手绘 SVG 上的实时列车位置、轨道图节点/边、车站（无地图库，保持 jar
    精简）。列车位置每 0.5 秒刷新，拓扑每 10 秒刷新。
  - **列车列表与详情** ——在线列车及其状态/速度/朝向；点击查看车厢、导航目标，以及每列车
    的用户元数据（显示名、分类、线路、颜色、备注）。
  - **路径搜索** ——在实时轨道图上运行有界深度的 k 最短路 DFS。当 CRN 存在时，起/终点
    选择器使用**站点标签**（每个标签聚合多个底层 Create 车站）；搜索会把标签展开为其全部
    车站，找出从任一起点到任一终点的最短路径。
  - **到发记录** ——通过在轮询周期之间比对每列车的 `navigating` 标志自动检测到站/发车
    （有无 CRN 均可）。每个车站保留 100 条环形缓冲；该区域展开时每 5 秒自动刷新。
  - **元数据**（只读） ——分类 / 线路 / 站点标签，每 10 秒通过反射从 CRN 的
    `GlobalSettings` 同步。在游戏内通过 CRN 管理，而非网页。CRN 缺失时回退到本地存储。

### 配置

可选：创建 `config/webboard-server.toml` 覆盖主机/端口：

```toml
[server]
host = "127.0.0.1"   # 绑定地址。请保持 localhost —— 仪表盘无认证。
port = 8080
maxWsConnections = 16
```

完整注释模板见 `src/main/resources/assets/create_web_board/webboard-server.toml.example`。
文件缺失则使用默认值。端口也可在启动时用 `-Dwebboard.port=<N>` 覆盖（便于多实例或测试）。

**安全警告**：仪表盘**无任何认证**。请仅绑定到 `127.0.0.1`。切勿将 8080 端口暴露到公网。

### 图标渲染

仪表盘可显示真实物品图标（JEI 风格 32×32 PNG，通过完整 `ItemRenderer` 管线渲染，包含染色
与 3D 模型变换——而非原始纹理文件）。两种途径：

- **联机主机 / 单人**：客户端将每个已注册物品渲染到离屏 FBO，并在加入时把 PNG 上传到服务
  端。期间会临时禁用 Iris shaderpack（Iris 替换了原版渲染着色器，否则离屏渲染会产生垃圾
  输出），渲染完成后重新启用。
- **专用服务端**：在装有本模组的客户端上执行 `/webboard export-icons`。它会渲染完整物品集
  并将 `webboard-icons.zip` 写入游戏目录。把该 zip 放入服务端 `config/` 文件夹，仪表盘会在
  下次启动时从中加载图标。

图标缓存于 `config/webboard-icons/`（或 `config/webboard-icons.zip`），通过
`GET /api/item-icon/{itemId}` 提供给浏览器，带 60 秒缓存头。

## 开发者与集成指南

### HTTP API

所有端点位于 `http://localhost:8080` 下。除特别说明外均为 JSON 进出。

**看板**

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/boards` | 所有看板快照数组 |
| GET | `/api/boards/{name}` | 单个看板；缺失返回 404 |
| PUT | `/api/boards/{name}` | 设置显示名（body `{"displayName":"..."}`，空串清除） |
| DELETE | `/api/boards/{name}` | 从仪表盘移除（仍在线的链路下次刷新会重现） |
| GET | `/api/boards/{name}/history` | 历史快照，最新在后：`[{"ts":<ms>,"lines":[...]}]` |
| PUT | `/api/boards/{name}/tags` | 替换标签（body `{"tags":["a","b"]}`） |
| PUT | `/api/boards/{name}/items` | 替换关联产品物品 id（body `{"itemIds":[...]}`） |

**网络**

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/networks` | 列出所有应力网络 |
| POST | `/api/networks` | 创建（body `{"name":"...","members":[{boardName,role,label,lineIndex}, ...]}`） |
| PUT | `/api/networks/{id}` | 更新名称 + 成员 |
| DELETE | `/api/networks/{id}` | 删除 |

`role` ∈ `producer`（默认）/ `consumer` / `storage`。`lineIndex` 默认 0。

**列车**（实时数据，只读 —— 来自 `TrainMirrorService`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/trains` | 所有实时列车快照（位置、速度、朝向、状态、导航目标、车厢） |
| GET | `/api/trains/by-id/{id}` | 单列列车快照；缺失返回 404 |
| GET | `/api/trains/graph` | 当前轨道图拓扑（节点、边、车站） |
| GET | `/api/trains/health` | `{"status":"ok","trains":N,"crn":"detected"/"absent","crnLines":M,"departures":K}` |
| GET | `/api/routes/search?from=...&to=...&maxResults=...` | 有界深度 k 最短路 DFS。`from`/`to` 可传 CRN 站点标签名（展开为其全部车站）或原始 Create 车站名。返回途径、总距离、预计行程时间。 |
| GET | `/api/departures?station=...&limit=...` | 某车站最近的到/发记录（省略 `station` 则返回全部） |
| GET | `/api/departures/all?limit=...` | 所有车站最近的到/发记录 |

**列车元数据**（分类 / 线路 / 标签从 CRN 只读同步；每列车配置可写）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/train-categories` | 列车分类（来自 CRN `GlobalSettings`；回退到本地存储） |
| GET | `/api/train-lines` | 列车线路（来自 CRN；回退到本地） |
| GET | `/api/station-tags` | 站点标签（来自 CRN；回退到本地）。每个标签携带 `stationNames[]` —— 其聚合的 Create 车站名。 |
| GET | `/api/train-metadata` | 所有列车的用户配置 |
| PUT | `/api/train-metadata/{trainId}` | 新增/更新单列车配置（body `{"displayName","categoryId","lineId","color","notes"}`） |
| DELETE | `/api/train-metadata/{trainId}` | 删除单列车配置 |

**其他**

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/source-labels` | 显示源类型 id → 本地化标签映射 |
| GET | `/api/health` | `{"status":"ok","boards":N,"wsConnections":M}` |
| GET | `/api/item-icon/{itemId}` | 物品图标 PNG（60 秒缓存）；无则 404 |
| POST | `/api/item-icon/{itemId}` | 上传渲染好的 PNG（body = 原始 PNG，名称通过 `X-Item-Name` URL 编码头传递） |
| GET | `/api/icon-pack/status` | `{"count":N,"initialized":bool}` |
| POST | `/api/items/names` | 批量解析物品 id → 名称（body `{"items":[...]}`） |
| GET | `/api/items/search?q=...&limit=50` | 按 id 或名称子串搜索物品 |

**WebSocket**：`ws://localhost:8080/ws`。连接时收到
`{"type":"snapshot","boards":[...]}`。服务端推送 `{"type":"update","board":{...}}`（也用于
离线状态切换）与 `{"type":"remove","name":"..."}`。

### 持久化文件

所有路径相对于服务端工作目录。

| 文件 | 格式 | 用途 |
|---|---|---|
| `config/webboard-boards.json` | JSON | 看板快照 + 历史（每看板上限 200 条）+ 标签 + 物品 id。防抖 5 秒刷盘，原子临时文件移动。 |
| `config/webboard-networks.json` | JSON | 应力网络定义。每次 CRUD 立即写入（原子移动）。 |
| `config/webboard-trains.json` | JSON | 每列车用户元数据（displayName、categoryId、lineId、color、notes）。每次 CRUD 立即写入（原子移动）。分类 / 线路 / 站点标签也在此持久化，作为 CRN 缺失时的本地回退。 |
| `config/webboard-icons/` | 目录 | 渲染好的物品图标 PNG + `names.json`。3 秒刷盘。 |
| `config/webboard-icons.zip` | zip | 可选的离线图标包，供专用服务端使用。 |
| `config/webboard-server.toml` | TOML | 可选的主机/端口/maxWsConnections 覆盖。 |

被移除的看板会以 `status: "removed"` 保留在 `webboard-boards.json` 中以便分析；
`GET /api/boards` 会跳过它们。

### 模组加载与同步

`META-INF/neoforge.mods.toml` 声明了 `displayTest = "IGNORE_SERVER_VERSION"`。效果：服务端
必须安装本模组，但未安装的客户端仍可加入，不会因版本不匹配被踢出。开关按钮仅在装有本模组
的客户端上渲染。

依赖：`minecraft [1.21.1,1.22)`、`neoforge [21.1.219,)`、`create [6.0.10,)`（均必需，双向）；
`flywheel`、`ponder`、`create_railways_navigator [0.9.0,)` 可选。CRN 在服务端启动时通过
`ModList.get().isLoaded("createrailwaysnavigator")` 检测，并经反射桥接（`CrnBridge`）——存在
时，列车调度图会只读同步 CRN 的分类 / 线路 / 站点标签；缺失时降级为 Create 原生数据加本地
维护的元数据。

### 构建

```bash
gradle build --no-daemon --stacktrace
```

输出：`build/libs/create_web_board-1.21.1-<version>.jar`。Javalin、Jetty、kotlin-stdlib 通过
jarJar 打包进 `META-INF/jarjar/`——模组自包含，无额外运行时依赖。

### 测试

```bash
gradle test --no-daemon
```

覆盖：PNG 编解码往返（无需 GL）、TOML 配置加载器边界情况、临时端口上的 Javalin HTTP+WS
实时集成、看板注册表监听器语义、断言 Javalin/Jetty/kotlin 类确已打包的 jar 内容健全性检查、
列车元数据存储 CRUD + 持久化往返、快照图上的路径搜索 BFS 正确性、列车轮询器节拍与 8 方位
罗盘方位数学，以及到发历史环形缓冲语义。共 **71 个测试**，全部在 CI 中无需 GPU 运行。

### 文档

- [CLAUDE.md](CLAUDE.md) —— 智能体协作规则（codegraph 查询、构建/测试、陷阱）。
- [docs/PRD.md](docs/PRD.md) —— 产品需求。
- [docs/research/train-dispatch-map-prd.md](docs/research/train-dispatch-map-prd.md) —— 列车调度图设计（0.7.1）。
- [docs/adr/0001-javalin-embedded.md](docs/adr/0001-javalin-embedded.md) —— 为何内嵌 Javalin。
- [CHANGELOG.md](CHANGELOG.md) —— 版本历史。发行说明源自此文件。

## 发行策略

**版本号不随功能递增。** 仅当维护者明确要求时才剪切新版本。两次发行之间，新代码合入 `main`，
CI 通过 `build` 工作流验证，但在维护者要求发行前**不会推送 tag，也不会发布 GitHub Release**。

每次发行的说明从 [CHANGELOG.md](CHANGELOG.md) 中对应的 `## [x.y.z]` 条目提取——参见其中
的 *Keep a Changelog* 格式。剪切发行的步骤：

1. 在 `CHANGELOG.md` 添加 `## [x.y.z] - YYYY-MM-DD` 条目描述变更。
2. 将 `gradle.properties` 中的 `mod_version` 提升至对应版本。
3. 提交，然后 `git tag v<x.y.z>` 并 `git push origin v<x.y.z>`。
4. `release` 工作流构建 jar 并使用该 changelog 条目作为正文创建 GitHub Release。

## 许可证

MIT。见 `gradle.properties`（`mod_license = MIT`）。
