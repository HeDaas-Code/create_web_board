# 火车调度图集成实施分析（第二轮深挖）

> 日期：2026-06-28
> 基于第一轮可行性报告，按用户决策（**弃用 SnR，只用 CRN + Create 原生 API；列车分类套用 CRN**）做的源码级深挖。
> 三路调研覆盖：① CRN 数据模型；② CRN 事件与工具类；③ Create 轨道图遍历与列车定位。
> 所有结论均来自源码直读（CRN 分支 `mc/1.21.1-DL3`、Create 分支 `mc1.21.1/dev`），URL 见文末。

---

## 1. 核心结论

### 1.1 列车分类套用 CRN：成立且合适

`TrainCategory`（`de.mrjulsen.crn.data.TrainCategory`）正合适做"货运/客运/混合"分类：

| 属性 | 说明 |
|---|---|
| 类型 | 普通 `class`（非 record） |
| 字段 | `UUID id`、`String name`、`DLColor color`、`Lock owner`、`Owner lastEditor`、`long lastEditedTime` |
| 常量 | `MAX_NAME_LENGTH = 32` |
| equals/hashCode | **仅基于 name**（同名视为相等） |
| 默认预设 | **无内置预设**（"Freight"/"Passenger" 都要玩家或我们 mod 自己创建） |
| 持久化 | 走 `GlobalSettings`，落盘 `<world>/data/createrailwaysnavigator_global_settings.nbt` |
| 已接入过滤 | `UserSettings.navigationExcludedTrainCategories`（`Set<UUID>`）+ `isTrainExcludedByUser()`——乘客可在路径规划中按 category 排除 |

**为什么选 category 而非 line**：
- `TrainCategory` 已接入 CRN 导航过滤管线，把货运列车统一打上某 category，乘客就能在导航里排除货运——与 CRN 既有机制天然契合。
- `TrainLine` 主要驱动 `TrainData.resolveTrainDisplayName()` 的对外展示文案，无过滤语义。更适合做"具体线路名"（如"货运北环线"）。
- 两者可组合：line=具体线路名，category=货运/客运粗分类。

**列车关联 category 的方式**：CRN 不直接在 Train 上挂字段，而是通过时刻表指令 `TravelSectionInstruction`（id `crn:travel_section`）把 category UUID 写进该指令 NBT。运行时由 `TrainData` 解析成 `ScheduleSection`，挂当前段。

**第三方读"这列车当前 category"**（服务端）：
```java
Optional<TrainData> td = TrainListener.getTrainData(train.id);  // train.id 即 Create Train.id (UUID)
Optional<TrainCategory> cat = td.flatMap(d -> d.getCurrentSection().getTrainCategory());
```

**关键约束**：CRN 不会自动识别货运/客运，它是纯标签。列车实际装载货物得从 Create 的 `carriage.storage.getAllItems()` 读（见 §3.3）。

### 1.2 数据来源已全部锁定，无缺口

| 调度图要素 | 数据来源 | API 入口 |
|---|---|---|
| 轨道拓扑 | Create `TrackGraph` | `Create.RAILWAYS.trackNetworks` → `graph.getNodes()` + `getConnectionsFrom(node)` |
| 站点 | Create `GlobalStation` | `graph.getPoints(EdgePointType.STATION)` |
| 列车实时位置 | Create `TravellingPoint` | `train.carriages.get(i).getLeadingPoint().getPosition(train.graph)` |
| 列车状态 | Create `Train` | `train.currentStation` / `navigation.destination` / `occupiedSignalBlocks` / `derailed` |
| 时刻表 | Create `ScheduleRuntime` | `train.runtime.schedule.entries` + `currentEntry` + `state` |
| ETA 预测 | Create + CRN 双源 | Create `runtime.submitPredictions()` 或 CRN `TrainUtils.getDeparturesAtStationName()` |
| 货运/客运分类 | CRN `TrainCategory` | `TrainListener.getTrainData(train.id).getCurrentSection().getTrainCategory()` |
| 线路配色 | CRN `TrainLine` | `TrainData.getCurrentSection().getTrainLine()` |
| 站点标签/站台号 | CRN `StationTag` | `GlobalSettings.getInstance().getOrCreateStationTagFor(stationName)` |
| 列车货物 | Create `Carriage.storage` | `carriage.storage.getAllItems()` / `getFluids()` |
| 历史到发 | CRN `DepartureHistory` | `DepartureHistory.getDeparturesAtStation(name)` / `Stats.ofStation(name)` |
| 实时到/发事件 | CRN `TrainArrivalAndDepartureEvent` | `CRNEventsManager.getEventOptional(TrainArrivalAndDepartureEvent.class)` |

### 1.3 意外发现：Create 自带 `compat.trainmap` 火车地图模块

Create 6.0.10 源码里有完整的 `compat.trainmap` 包（`TrainMapManager` / `TrainMapSync` / `TrainMapSyncClient`），就是官方的火车地图实现。这是绝佳参考——我们要做的事和它高度重合，可直接复刻它的遍历逻辑和同步节奏：
- **拓扑遍历**：`TrainMapManager.redrawAll` / `drawPoints` 直接遍历 `CreateClient.RAILWAYS.trackNetworks` 画轨道线和站点
- **列车位置同步**：`TrainMapSync.createEntry` 给出标准做法——`carriage.getLeadingPoint().getPosition(train.graph)` 算世界坐标
- **同步频率**：`lightPacketInterval = 5 tick (0.25s)`、`fullPacketInterval = 10 tick (0.5s)`
- **状态枚举**：`TrainState`（RUNNING / RUNNING_MANUALLY / DERAILED / SCHEDULE_INTERRUPTED / CONDUCTOR_MISSING / NAVIGATION_FAILED）

我们做 Web 端调度图可直接照搬这些数值。

---

## 2. CRN 数据模型深挖（用于列车分类、线路、站点标签）

### 2.1 TrainCategory vs TrainLine（结构同构，语义分工）

两者都是普通 class，字段几乎完全对称（id/name/color/owner/lastEditor/lastEditedTime），equals/hashCode 都仅基于 name。语义分工由消费方决定：

| 维度 | TrainCategory | TrainLine |
|---|---|---|
| 用途 | 导航过滤分类 | 对外展示线路名 |
| 驱动行为 | `UserSettings.navigationExcludedTrainCategories` 排除列车 | `TrainData.resolveTrainDisplayName()` 显示文案 |
| 推荐用于 | 货运/客运粗分类 | 具体线路名（"货运北环线"） |

**第三方读取**（服务端）：
```java
GlobalSettings gs = GlobalSettings.getInstance();
ImmutableList<TrainCategory> cats = gs.getAllTrainCategories();
Optional<TrainCategory> cat = gs.getTrainCategory(uuid);
Optional<TrainCategory> catByName = gs.getTrainCategoryByName("Freight");
TrainCategory newCat = gs.createOrGetTrainCategory("Freight", owner);  // 不存在则创建
```

### 2.2 TrainInfo（record，临时视图）

`public record TrainInfo(TrainLine line, TrainCategory category)`——临时组合视图，不持久化、不持 trainId。通过 `TrainData.getTrainInfo(scheduleIndex)` 取得。第三方一般不直接用，直接走 `ScheduleSection.getTrainCategory()` / `getTrainLine()`。

### 2.3 StationTag（站点分组+站台号）

```java
public class StationTag {
    protected UUID id;
    protected TagName tagName;
    protected Map<String /*Station Name*/, StationInfo> stations;  // 以站点名为键
    // 内嵌 record
    public static record StationInfo(String platform) {  // 只有站台号一个字段
        public static final int MAX_PLATFORM_NAME_LENGTH = 8;
    }
    public static record ClientStationTag(String tagName, String stationName, StationInfo info, UUID tagId) {}
}
```

**关键**：
- 通过**站点名字符串**关联 GlobalStation（不持引用或 UUID），支持 glob 通配符
- 一个站点可挂多个 tag（多对多允许）
- 站台号存在 `(tag, station)` 组合上，不是全局站点属性
- 客户端用 `ClientStationTag`（NBT 同步快照），服务端用 `StationTag`

### 2.4 GlobalSettings（服务端单例）

**访问**：
```java
public synchronized static GlobalSettings getInstance();   // 懒加载，首次调用时 open(server)
public static boolean hasInstance();
public static void clearInstance();   // 停服用
```

**持有集合**（全部并发）：
- `Map<UUID, StationTag> stationTags`
- `Map<UUID, TrainCategory> trainCategories`
- `Map<UUID, TrainLine> trainLines`
- `Set<String> stationBlacklist` / `trainBlacklist`

**持久化**：**不是 vanilla SavedData**，是自定义 NBT 文件 `<world>/data/createrailwaysnavigator_global_settings.nbt`（旧名 `.dat`），`NbtIo.writeCompressed` / `readCompressed`。`DATA_VERSION = 2`。

**生命周期挂载在 `ModCommonEvents`**：`SERVER_STOPPING` → `clearInstance`；`SERVER_LEVEL_SAVE` → `save()`。

### 2.5 GlobalSettingsClient（客户端门面）

**重要约束**：`GlobalSettings` 仅服务端存在，客户端拿不到实例。客户端必须用 `GlobalSettingsClient`（纯静态门面，请求-响应式 C2S 包，**异步回调**，无持续镜像）。

我们 mod 走服务端镜像模式（主线程读 → WS 推前端），**只用 `GlobalSettings`，不碰 `GlobalSettingsClient`**。

---

## 3. CRN 事件总线与工具类

### 3.1 CRNEventsManager API

```java
public static <T extends AbstractCRNEvent<?>> T getEvent(Class<T> clazz);              // 未注册抛 NPE
public static <T extends AbstractCRNEvent<?>> Optional<T> getEventOptional(Class<T> clazz);  // 防御式
public static <T extends AbstractCRNEvent<?>> boolean isRegistered(Class<T> clazz);
public static void registerEvent(AbstractCRNEvent<?> eventInstance);  // 第三方注册自定义事件
public static void clearEvents();
```

**IEvent 接口**（事件订阅）：
```java
public interface IEvent<T> {
    void register(String modid, T event);   // modid 同时是反注册 key
    void unregister(String modid);
}
```
**没有优先级参数**，每个 modid 只能挂一个回调（后注册覆盖前者）。

**反注册是延迟的**：`unregister` 只把 modid 加入 `idsToRemove`，真正移除发生在下一次 `event.run()` 末尾的 `tickPost()`。

### 3.2 全部 9 个可订阅事件

| 事件 | 回调签名 | 承载数据 | 触发时机 | 侧 |
|---|---|---|---|---|
| **SubmitTrainPredictionsEvent** | `(Train, Collection<TrainDeparturePrediction>, int entryCount, int accumulatedTime, int current)` | Create 原生 `TrainDeparturePrediction` 集合 | `ScheduleRuntime.submitPredictions` 返回时 | 服务端主线程 |
| **TrainArrivalAndDepartureEvent** | `(Train, Optional<GlobalStation> current, boolean arrival)` | arrival=true 到站 / false 离站 | `Train.arriveAt` / `leaveStation` 末尾 | 服务端主线程 |
| **TrainDestinationChangedEvent** | `(Train, GlobalStation current, GlobalStation next, int nextIndex)` | 当前站/下一目标站/entry 索引 | `startCurrentInstruction` 返回 DiscoveredPath 时 | 服务端主线程 |
| **ScheduleResetEvent** | `(Train, boolean soft)` | soft=true 软重置 / false 硬重置 | 调度设置/丢弃/初始化时 | 服务端主线程 |
| **TotalDurationTimeChangedEvent** | `(Train, long oldDuration, long newDuration)` | 旧/新总运行时长 | TrainData 更新总时长时 | **CRN 后台线程** |
| **DefaultTrainDataRefreshEvent** | `Runnable`（无参） | 无 | 客户端每 100 tick | 客户端 |
| **GlobalTrainDisplayDataRefreshEventPre** | `Runnable` | 无 | Create 显示数据刷新前（约 5s） | 服务端主线程 |
| **GlobalTrainDisplayDataRefreshEventPost** | `Runnable` | 无 | Create 显示数据刷新后 | 服务端主线程 |
| `RouteDetailsActionsEvent` | — | — | **未启用**（源文件 `.java.pain`，注册行被注释） | — |

**触发线程关键点**：
- 事件总线本身不切线程，回调运行在调用 `event.run()` 的线程
- 大部分事件在**服务端主线程**触发
- `TotalDurationTimeChangedEvent` 在 CRN 自起的 `"CRN Train Listener"` 后台线程触发
- CRN 自己的回调几乎都只是 `queueTrainListenerTask()` 把重活排到后台线程——**第三方回调也必须只做轻量读取/标记，把 IO 丢到自己的线程**

**`Optional<GlobalStation>` 为空的时机**：`arriveAt(station)` 传 null、`leaveStation` 时 `currentStation` 局部变量为 null（列车不在任何站/已脱轨等异常）。

### 3.3 TrainUtils 工具类

**核心方法签名**：
```java
public static List<TrainStop> getDeparturesAtStationName(
    String stationName,      // 支持通配符 *
    UUID selfTrain,          // 排除的列车 ID，null 不排除
    boolean realTimeOnly,    // true 只返回当前有效的
    boolean allowDuplicates  // true 允许同列车多次出现
);

public static Map<String, Collection<TrainPrediction>> allPredictionsRaw();   // 站名 -> 预测集
public static Collection<GlobalStation> getAllStations();                     // 带缓存
public static Set<String> getAllStationNames();
public static Optional<Train> getTrain(UUID trainId);
public static Set<Train> getTrains(boolean onlyValid);
public static Set<Train> getDepartingTrainsAt(String station);
public static GlobalRailwayManager getRailwayManager();                       // = Create.RAILWAYS
public static boolean isTrainValid(Train train);
public static boolean isTrainUsable(Train train);
```

**调用约束**：
- **必须服务端调用**（依赖 `TrainListener`，仅服务端启动）
- **不需要传 Level**（仅几何方法如 `getNearestTrackStation` 需要）
- `statusByDestination` 是非并发 HashMap，CRN 在后台线程改——建议在 `GlobalTrainDisplayDataRefreshEventPost` 回调点读

### 3.4 TrainStop（核心数据结构，21 字段）

```java
public class TrainStop implements Comparable<TrainStop> {
    UUID trainId;              String trainName;        TrainIconType trainIcon;
    TrainInfo trainInfo;       int scheduleIndex;       int sectionIndex;
    String scheduleTitle;      String terminusText;     boolean isCustomTitle;
    int stayDuration;
    long scheduledArrivalTime;     long scheduledDepartureTime;
    long realTimeArrivalTime;      long realTimeDepartureTime;
    int cycle;                     int realTimeCycle;
    ClientStationTag tag;          ClientStationTag realTimeTag;
    TrainState trainState;         int realTimeTicksUntilArrival;
    boolean simulated;             long simulationTime;
    // getter 略
}
```

**`TrainState` 枚举**：`BEFORE(-2)` / `ANNOUNCED(-1)` / `STAYING(0)` / `AFTER(1)`。

**关键约束**：
- `getTag()` 仅服务端可用（要查 GlobalSettings），客户端调抛 `RuntimeSideException`
- 客户端用 `getScheduledStationTag()` / `getRealTimeStationTag()`（返回 `ClientStationTag`）
- 支持 `toNbt()` / `fromNbt()`，可跨网络传输

### 3.5 NavigableGraph（不推荐第三方直接用）

**重要发现**：`NavigableGraph` 不是单例、不是静态、**每次 `searchRoutes` 都 `new` 一个新图**。核心映射 `nodesByTag` / `nodesByTrain` / `edgesByTag` 都是 `protected`，**无 public getter**——第三方无法直接枚举整网拓扑。

**抽象层级**：节点 = `StationTag`（站点），边 = "某列车从 A 开到 B"（承载 `TrainPrediction`）。是站点级抽象，**不是 Create TrackGraph 原始轨道拓扑**。

**第三方唯一可用入口**：
```java
public static List<Route> searchRoutes(
    StationTag start, StationTag destination, UUID playerId, boolean avoidTransfers
);
```
- 务必在自己线程调用（建图开销大）
- 结果 `List<Route>` 可序列化为 JSON 推前端
- 客户端不能直接搜索，需走 CRN 网络包 `NavigatePacketData`

**要画整网线路图建议**：用 `TrainUtils.allPredictionsRaw()` + `getAllStations()` 自建站点级邻接图（更自由、不依赖 protected 字段）。

### 3.6 PredictionTimes / DepartureHistory

**PredictionTimes**（不可变值对象，挂在 `TrainPrediction` 上）：
```java
long refreshTime;        // 本次刷新基准
long arrivalTime;        // 到站时间
long defaultDepartureTime;   // 调度默认离站
long minDepartureTime;       // 最早可能离站
long departureTime();        // 实际计算用（综合 minStay/stay）
long arrivalIn();            // arrivalTime - refreshTime
long departureIn();          // departureTime - refreshTime
long stayDuration();
```
`TrainPrediction` 通过 `scheduled()` 和 `realTime()` 两个 `PredictionTimes` 暴露计划/实时两组时间。

**DepartureHistory**（服务端静态，全静态方法）：
```java
public static Map<String, Data> getDeparturesAtStation(String stationFilter);  // 通配符
public static long getLatestDepartureFor(ETrainFilter filter, Train train, String stationName);
```
`Data` 暴露 `getLastDeparturesByLine()` / `getLastDeparturesByCategory()` / `getLastDeparturesByTrainName()`。客户端要历史需走 `GetStationDepartureHistoryPacketData` 网络包。

### 3.7 稳定性分级

| 类/包 | 稳定性 | 说明 |
|---|---|---|
| `api.IPredictableWaitCondition` | 稳定 | 官方 api（仅此一个） |
| `event.CRNEventsManager` / `IEvent` | 较稳定 | 总线骨架 |
| `event.events.*`（9 个事件） | 较稳定 | 承载字段可能随版本增减 |
| `data.train.TrainUtils`（常用方法） | 较稳定 | 核心方法长期存在 |
| `data.train.TrainStop` / `TrainPrediction` | 中等 | 字段多，版本间有重构 |
| `data.navigation.NavigableGraph` | **不稳定** | 内部 Dijkstra，protected 字段无 getter，重构风险高 |
| `TrainListener.statusByDestination` | 不稳定 | 直接读 public Map 可用但属内部结构，非并发安全 |

**api 包只有 1 个接口**——事件、TrainUtils、TrainStop、NavigableGraph、Route、DepartureHistory **全部不在 api 包**，按包名约定属内部 API，版本间可能变动。需做版本兼容封装 + 反射兜底。

---

## 4. Create 轨道图遍历与列车定位

### 4.1 TrackGraph 遍历（精确字段与方法）

```java
public class TrackGraph {
    Map<TrackNodeLocation, TrackNode> nodes;
    Map<Integer, TrackNode> nodesById;
    Map<TrackNode, Map<TrackNode, TrackEdge>> connectionsByNode;  // 邻接表（IdentityHashMap）
    EdgePointStorage edgePoints;
    Map<ResourceKey<Level>, TrackGraphBounds> bounds;
    public UUID id; public Color color;

    public Set<TrackNodeLocation> getNodes();                    // 注意返回 Location 不是 Node
    public TrackNode locateNode(TrackNodeLocation position);
    public TrackNode locateNode(Level level, Vec3 position);
    public TrackNode getNode(int netId);
    public Map<TrackNode, TrackEdge> getConnectionsFrom(TrackNode node);  // 邻接边入口
    public TrackEdge getConnection(Couple<TrackNode> nodes);
    public <T extends TrackEdgePoint> Collection<T> getPoints(EdgePointType<T> type);
    public <T extends TrackEdgePoint> T getPoint(EdgePointType<T> type, UUID id);
}
```

**遍历边的标准写法**（参考 `TrainMapManager.redrawAll`）：
```java
for (TrackNodeLocation loc : graph.getNodes()) {
    TrackNode n1 = graph.locateNode(loc);
    for (Map.Entry<TrackNode, TrackEdge> e : graph.getConnectionsFrom(n1).entrySet()) {
        TrackNode n2 = e.getKey(); TrackEdge edge = e.getValue();
        // 每条物理边存了两个方向，去重用 other.hashCode() > hashCode() 跳过一半
    }
}
```

**EdgePointType 三个常量**：`STATION` → `GlobalStation`、`SIGNAL` → `SignalBoundary`、`OBSERVER` → `TrackObserver`。

### 4.2 TrackNode / TrackNodeLocation（世界坐标）

```java
public class TrackNode {
    int netId;
    Vec3 normal;
    TrackNodeLocation location;
    public TrackNodeLocation getLocation();
    public int getNetId();
    public Vec3 getNormal();
}

public class TrackNodeLocation extends Vec3i {   // 坐标已 ×2 量化
    public ResourceKey<Level> dimension;
    public int yOffsetPixels;
    public Vec3 getLocation();           // 世界坐标 Vec3 = (x/2, y/2 + yOffsetPixels/16, z/2)
    public ResourceKey<Level> getDimension();
}
```

**世界坐标**：`node.getLocation().getLocation()`（第一个返回 `TrackNodeLocation`，第二个返回 `Vec3`）。
**维度**：`node.getLocation().getDimension()` 或 `.dimension`（public 字段）。
**注意**：`Vec3i` 的 `getX()/getY()/getZ()` 返回量化值（×2），要除以 2 才是方块坐标，`getLocation()` 已换算。

### 4.3 TrackEdge（边几何）

```java
public class TrackEdge {
    public TrackNode node1, node2;        // 两端（public 字段，直接读）
    BezierConnection turn;                // null=直道
    EdgeData edgeData;
    boolean interDimensional;
    TrackMaterial trackMaterial;

    public double getLength();            // 跨维度=0；曲线=turn.getLength()；直道=两点距离
    public boolean isTurn();
    public boolean isInterDimensional();
    public BezierConnection getTurn();
    public EdgeData getEdgeData();
    public Vec3 getPosition(TrackGraph graph, double t);   // t∈[0,1] 边上世界坐标
    public Vec3 getNormal(TrackGraph graph, double t);
    public Vec3 getDirection(boolean fromFirst);
}
```

**EdgeData** 承载：`UUID singleSignalGroup`（信号组）、`List<TrackEdgePoint> points`（按位置排序）、`List<TrackEdgeIntersection> intersections`（道岔/平面交叉）。站点/信号/观察器都挂在 `EdgeData.points` 里，同时存在全局 `EdgePointStorage`（双索引）。

### 4.4 GlobalStation 在图上的位置

继承链：`GlobalStation` → `SingleBlockEntityEdgePoint` → `TrackEdgePoint`。核心字段：
```java
public UUID id;
public Couple<TrackNodeLocation> edgeLocation;   // 所在边的两端 node 位置
public double position;                          // 沿边距 node1 的距离
public String name;
public WeakReference<Train> nearestTrain;
public boolean assembling;
public BlockPos blockEntityPos;                  // 站台方块坐标
public ResourceKey<Level> blockEntityDimension;
```

**求世界坐标**（参考 `TrainMapManager.drawPoints`）：
```java
TrackNode n1 = graph.locateNode(station.edgeLocation.getFirst());
TrackNode n2 = graph.locateNode(station.edgeLocation.getSecond());
TrackEdge edge = graph.getConnection(Couple.create(n1, n2));
double t = station.getLocationOn(edge) / edge.getLength();
Vec3 world = edge.getPosition(graph, t);
```

**枚举一张图所有站点**：`graph.getPoints(EdgePointType.STATION)` → `Collection<GlobalStation>`。

**是否正停靠列车**：`station.getPresentTrain()`（基于 `train.getCurrentStation() == this`）。

### 4.5 列车实时位置（TravellingPoint）

**修正之前的猜测**：没有 `CarriageBogey.getLeadingPoint()`。正确链路：
- `Carriage.getLeadingPoint()` → `leadingBogey().leading()` → `points.getFirst()` → `TravellingPoint`
- `Carriage.getTrailingPoint()` → `trailingBogey().trailing()` → `points.getSecond()`

```java
public class TravellingPoint {
    public TrackNode node1, node2;   // 当前边的两端
    public TrackEdge edge;
    public double position;          // 沿 edge 距 node1
    public boolean blocked;
    public boolean upsideDown;

    public Vec3 getPosition(TrackGraph graph);   // 世界坐标 Vec3
    // 内部: t = position / edge.getLength(); edge.getPosition(graph, t) + edge.getNormal(graph,t)*(±1)
}
```

**转世界坐标**：`tp.getPosition(train.graph)`（需传 graph 处理 yOffsetPixels）。
**维度**：`tp.node1.getLocation().getDimension()`。

### 4.6 "列车在哪站/哪两站之间"判定

```java
GlobalStation current = train.getCurrentStation();   // null=行驶中
boolean inStation = current != null;
boolean navigating = train.navigation.isActive();    // destination != null
GlobalStation dest = train.navigation.destination;
double distLeft = train.navigation.distanceToDestination;
```

- 在站：`inStation == true` → 站名 `current.name`
- 两站之间：`inStation == false && navigating == true` → 终点 `dest.name` + 剩余距离
- 静止/手动：两者都 false，只能给几何位置

**路径序列**：`train.navigation.currentPath` 是 `List<Couple<TrackNode>>`（一串边），可逐条 `graph.getConnection(couple)` 还原为 TrackEdge 画线。

### 4.7 客户端同步限制（关键）

`AddTrainPacket` 用 `Train.STREAM_CODEC`，**只同步静态结构字段**：
```
id, owner, carriages(含 bogeys 的 TravellingPoint 初始位置), carriageSpacing,
doubleEnded, name, icon, mapColorIndex
```

**不同步**：`graph`、`navigation`、`currentStation`、`speed`、`occupiedSignalBlocks`、`derailed`、`fuelTicks`。

**客户端 TravellingPoint 不会被 `travel()` 推进**（travel 只在服务端 `Train.tick` 调），所以客户端拿到的位置是**登录瞬间快照**，之后过时。

**结论**：我们必须走服务端镜像模式（主线程读 → WS 推前端），不能依赖客户端数据。

### 4.8 Create 官方 TrainMapSync（强烈建议复刻其节奏）

`compat.trainmap.TrainMapSync` 是按需推送的轻量包，只发给"打开过火车地图"的玩家。

**每列车 `TrainMapSyncEntry` 同步**：
- `positions: Float[]`——每车厢 6 个 float（leading x/y/z + trailing x/y/z），**已是世界坐标**
- `dimensions: List<ResourceKey<Level>>`——每车厢所在维度
- `state: TrainState`（RUNNING / RUNNING_MANUALLY / DERAILED / SCHEDULE_INTERRUPTED / CONDUCTOR_MISSING / NAVIGATION_FAILED）
- `signalState: SignalState`（NOT_WAITING / WAITING_FOR_REDSTONE / BLOCK_SIGNAL / CHAIN_SIGNAL）
- `fueled`、`backwards`、`targetStationDistance`、`ownerName`、`targetStationName`、`waitingForTrain`

**频率**：`lightPacketInterval = 5 tick (0.25s)`、`fullPacketInterval = 10 tick (0.5s)`。

**目标站**：`train.getCurrentStation()`（在站→distance=0）或 `train.navigation.destination.name + distanceToDestination`。

### 4.9 轮询性能与频率建议

**一次全遍历代价**：
- 拓扑遍历：`Σ graph.getNodes() × getConnectionsFrom(n).entrySet()`——纯 HashMap 读，O(V+E)，几百节点+边**亚毫秒级**
- 列车位置：每辆读 `carriage.getLeadingPoint().getPosition(graph)`，列车通常几辆到几十辆，**微秒~低毫秒级**
- **唯一较重**：`Navigation.findPathTo`/`search`（Dijkstra）——**不要在轮询里调**，只读已算好的 `navigation.destination`/`currentPath`/`distanceToDestination`

**线程安全**（重要）：`TrackGraph`、`Train`、`TravellingPoint` 全部**非线程安全**，只在服务端主线程被改。第三方轮询**必须在主线程执行**，或用主线程任务队列把"读快照"调度到主 tick 末尾再交给 WS 线程发送。

**建议频率**：
- 拓扑（图/站点/信号）：**10s** 足够（变化低频），更优是 `Create.RAILWAYS.version` 变化触发
- 列车位置/状态：**0.5s（10 tick）**，匹配 Create 官方 TrainMapSync 节奏，远快于 10s，足够做调度可视化
- 比 0.25s 更快没意义（列车 speed 单位是 block/tick，半 tick 位置差 <1 格）

---

## 5. 推荐架构（更新版）

```
┌──────────────────────────────────────────────────────────────────┐
│ 服务端（Minecraft 主线程 + 后台工作线程）                          │
│                                                                   │
│  TrainMirrorService（新增，仿 WebMirror 模式）                    │
│   ├─ 拓扑轮询（10s 或 version 触发）                              │
│   │   └─ 遍历 Create.RAILWAYS.trackNetworks → nodes/edges/stations│
│   ├─ 列车轮询（0.5s，主线程任务队列）                              │
│   │   └─ train.carriages.get(i).getLeadingPoint().getPosition()   │
│   │      + currentStation / navigation.destination / runtime      │
│   ├─ [可选] CRN 事件订阅（实时到/发推送）                          │
│   │   └─ TrainArrivalAndDepartureEvent → 标记脏 → 立即推送        │
│   ├─ [可选] CRN 数据读取                                          │
│   │   └─ TrainUtils.getDeparturesAtStationName() / allPredictions │
│   │      + GlobalSettings.getAllTrainCategories/Lines/StationTags │
│   │      + TrainListener.getTrainData(id).getCurrentSection()     │
│   └─ 推 BoardRegistry → WebSocketHub → 前端                       │
│                                                                   │
│  TrainMetadataStorage（新增，config/webboard-trains.json）        │
│   └─ 货运/客运分类用 CRN TrainCategory（不另建）                  │
│   └─ 仅存: 用户自定义标签、备注、自动推断初值缓存                 │
│                                                                   │
│  REST API                                                         │
│   ├─ /api/trains              GET 列车列表+状态                   │
│   ├─ /api/trains/{id}         GET 列车详情                        │
│   ├─ /api/track-graph         GET 拓扑（节点+边+站点）            │
│   ├─ /api/train-categories    GET/POST/PUT/DELETE CRN 分类 CRUD   │
│   ├─ /api/train-lines         GET/POST/PUT/DELETE CRN 线路 CRUD   │
│   ├─ /api/station-tags        GET/POST/PUT/DELETE CRN 站点标签    │
│   ├─ /api/routes/search       POST A→B 路径搜索（调 CRN）         │
│   └─ /api/departure-history   GET 站点历史到发                    │
└──────────────────────────────────────────────────────────────────┘
                              │ WebSocket + REST
┌──────────────────────────────────────────────────────────────────┐
│ 前端（现有 vanilla JS 看板，新增"火车调度"视图）                  │
│                                                                   │
│  视图切换: 看板 / 应力网 / 火车调度                               │
│                                                                   │
│  火车调度视图                                                     │
│   ├─ 拓扑图 (SVG): 节点=站点, 边=轨道, 列车点=在线列车            │
│   │   └─ 按 TrainLine 颜色着色，按维度分页                        │
│   ├─ 时刻表甘特图 (SVG): 时间轴 × 列车, 横杠=到发时段            │
│   │   └─ 数据来自 Schedule + predictionTicks / CRN TrainStop      │
│   ├─ 列车详情面板                                                 │
│   │   └─ 状态/速度/编组/货物/时刻表进度/ETA/当前 category/line   │
│   ├─ 站点详情面板                                                 │
│   │   └─ 即将到达列车列表 / 历史到发 / 站台号                    │
│   ├─ 路径搜索面板                                                 │
│   │   └─ A 站 → B 站，显示换乘方案                                │
│   └─ 配置面板                                                     │
│       └─ 创建/编辑 TrainCategory/TrainLine/StationTag             │
└──────────────────────────────────────────────────────────────────┘
```

**与现有架构契合点**：
- 服务端镜像复用 `WebMirror` 心跳轮询模式（拓扑 10s + 列车 0.5s）
- HTTP/WebSocket 复用现有 `HttpServer` + `WebSocketHub`
- 持久化：**列车分类不另建存储，直接用 CRN GlobalSettings**（已有 NBT 持久化）；用户自定义标签/备注用类似 `NetworkStorage` 的 JSON
- 前端复用现有 SVG 折线图基础设施扩展为拓扑图/甘特图
- CRN 软依赖模式：`ModList.isLoaded("createrailways_navigator")` 守卫 + 直接引用 CRN 类（编译期依赖 jar）+ ClassNotFoundException/反射兜底

---

## 6. 实施步骤（更新版，分三阶段）

### 阶段一：纯 Create 数据 + 基础调度图（零 CRN 依赖）

**目标**：可用的拓扑图 + 列车位置 + 列车详情，不依赖 CRN。

**后端**：
1. `TrainMirrorService`：服务端主线程任务队列，拓扑 10s / 列车 0.5s 双频轮询
2. 拓扑提取：遍历 `Create.RAILWAYS.trackNetworks`，序列化 nodes/edges/stations（含世界坐标+维度）
3. 列车提取：`train.id/name/speed/currentStation/navigation.destination/runtime/cargoes`
4. REST：`/api/trains`、`/api/track-graph`
5. WS：推送列车位置变化

**前端**：
1. 火车调度视图骨架，视图切换
2. SVG 拓扑图：站点节点 + 轨道边 + 列车点（按维度分页）
3. 列车详情面板：状态/速度/编组/货物/时刻表进度
4. 时刻表甘特图（用 `runtime.schedule` + `predictionTicks`）

### 阶段二：CRN 软依赖增强（分类/线路/标签/事件）

**目标**：接入 CRN 拿分类、线路、站台号、实时到发事件。

**后端**：
1. CRN 软依赖：`ModList.isLoaded` 守卫，编译期依赖 CRN jar
2. 读取 `GlobalSettings`：`getAllTrainCategories/Lines/StationTags`
3. 读取 `TrainListener.getTrainData(train.id).getCurrentSection()` 拿每列车当前 category/line
4. 订阅 `TrainArrivalAndDepartureEvent`：实时到/发推 WS
5. 订阅 `GlobalTrainDisplayDataRefreshEventPost`：周期性刷新 CRN 预测缓存
6. REST：`/api/train-categories`、`/api/train-lines`、`/api/station-tags`（CRUD 透传 CRN GlobalSettings）
7. REST：`/api/departure-history`（透传 `DepartureHistory`）

**前端**：
1. 拓扑图按 `TrainLine` 颜色着色
2. 站点按 `StationTag` 显示站台号
3. 列车详情显示当前 category/line
4. 实时到/发事件流（WS 推送）
5. 配置面板：CRN 分类/线路/站点标签 CRUD
6. 站点详情面板：即将到达列车 + 历史到发

### 阶段三：路径搜索（可选）

**目标**：Web 端发起 A→B 路径搜索。

**后端**：
1. REST `/api/routes/search`：服务端调 `NavigableGraph.searchRoutes(start, dest, playerId, false)`（**后台线程**）
2. 结果 `List<Route>` 序列化为 JSON

**前端**：
1. 路径搜索面板：选起点终点，显示换乘方案
2. 在拓扑图上高亮路径

---

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| CRN 事件/工具类属内部 API，跨版本可能变动 | 版本探测 + 反射兜底 + 缺失时降级为纯 Create 数据 |
| CRN 是 GPLv3，编译期依赖可能有许可证影响 | 评估法务；保守做法是纯运行时软依赖 + 反射（不静态链接） |
| Create 无列车生命周期事件 | 用 Mixin 拦截 `Train.arriveAt`/`leaveStation`/`addTrain`/`removeTrain`，或纯轮询 |
| `TrackGraph`/`Train` 非线程安全 | 所有读取必须在服务端主线程，用任务队列调度 |
| 拓扑图世界坐标→抽象线路图简化 | 先做"站点+连接关系"图（不画精确轨道），后续优化 |
| 列车位置精度 | 先用"当前站/下一站"粗粒度，足够调度图需求 |
| CRN 后台线程与主线程数据竞态 | 在 `GlobalTrainDisplayDataRefreshEventPost` 回调点读 CRN 数据（相对稳定） |
| 现有 `SourceLabels.java` 的 CRN 源 id 错误 | 独立小修：`createrailwaysnavigator:train_display` → `createrailwaysnavigator:advanced_display` |
| 现有文本镜像对 CRN `advanced_display` 源失效 | 调度图不走文本镜像，直接读 CRN API；保留 `SourceLabels` 修正只为标签准确 |

---

## 8. 待用户确认的设计决策

1. **列车分类 CRUD 入口**：直接透传 CRN `GlobalSettings`（用户在 Web 端建的分类会同步进 CRN 的 NBT 文件，游戏内 Navigator 也能看到）——还是我们自建一份镜像？**推荐透传**，避免双写不一致。
2. **路径搜索**：是否需要（阶段三）？还是只做被动展示？
3. **拓扑图样式**：地铁线路图风格（抽象）vs 真实轨道走向（按世界坐标）？前者更易读，后者更直观。
4. **多维度处理**：分页/分图层/合并？推荐分页（一张图一个维度）。
5. **轮询频率**：拓扑 10s + 列车 0.5s 是否可接受？列车 0.5s 会增加 WS 推送量。

---

## Sources

### CRN（分支 mc/1.21.1-DL3）
- TrainCategory: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/TrainCategory.java
- TrainLine: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/TrainLine.java
- TrainInfo: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/TrainInfo.java
- StationTag: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/StationTag.java
- GlobalSettings: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/storage/GlobalSettings.java
- GlobalSettingsClient: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/storage/GlobalSettingsClient.java
- TravelSectionInstruction: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/schedule/instruction/TravelSectionInstruction.java
- ScheduleSection: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/train/ScheduleSection.java
- TrainData: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/train/TrainData.java
- TrainListener: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/train/TrainListener.java
- CRNEventsManager: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/event/CRNEventsManager.java
- IEvent: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/event/IEvent.java
- 事件类目录: https://github.com/MisterJulsen/Create-Train-Navigator/tree/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/event/events
- TrainUtils: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/train/TrainUtils.java
- TrainStop: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/train/TrainStop.java
- TrainPrediction: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/train/TrainPrediction.java
- PredictionTimes: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/train/PredictionTimes.java
- DepartureHistory: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/train/DepartureHistory.java
- NavigableGraph: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/navigation/NavigableGraph.java
- Route: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/navigation/Route.java
- RoutePart: https://raw.githubusercontent.com/MisterJulsen/Create-Train-Navigator/mc/1.21.1-DL3/common/src/main/java/de/mrjulsen/crn/data/navigation/RoutePart.java

### Create（分支 mc1.21.1/dev）
- TrackGraph: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/TrackGraph.java
- TrackNode: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/TrackNode.java
- TrackNodeLocation: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/TrackNodeLocation.java
- TrackEdge: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/TrackEdge.java
- EdgeData: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/EdgeData.java
- EdgePointType: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/EdgePointType.java
- GlobalStation: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/station/GlobalStation.java
- Train: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/entity/Train.java
- Carriage: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/entity/Carriage.java
- CarriageBogey: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/entity/CarriageBogey.java
- TravellingPoint: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/entity/TravellingPoint.java
- Navigation: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/entity/Navigation.java
- GlobalRailwayManager: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/GlobalRailwayManager.java
- TrackGraphSync: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/TrackGraphSync.java
- **TrainMapManager（官方火车地图，必读参考）**: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/compat/trainmap/TrainMapManager.java
- **TrainMapSync（官方同步节奏参考）**: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/compat/trainmap/TrainMapSync.java
- TrainMapSyncClient: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/compat/trainmap/TrainMapSyncClient.java
