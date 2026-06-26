# Issue #1 — Scaffold + WebDisplaySource 注册

**Repo**: https://github.com/HeDaas-Code/create_web_board
**Worktree**: `~/Project/MC_MOD_DEV/create-web-board/`
**Branch**: `feature/issue-1-scaffold`

## 目标（验收标准）

1. `gradle build --no-daemon --stacktrace` 在本地 5s 内 PASS
2. GitHub Actions CI（`.github/workflows/build.yml`）绿
3. Mod jar 包含新类 `com.example.webboard.content.displaysource.WebDisplaySource`
4. In-game 测试：进单人生成世界 → 放 DisplayLink → 改 source 为"Web Board" → 不崩溃

## 已知坑（Hermes 已经踩过，CC 不要重复踩）

1. **slf4j version conflict**：NeoForge `strictly 2.0.9`，Javalin 想要 ≥2.0.13。**修法**：不要加 `slf4j-simple`，让 MC 自带的 Log4j2 处理（jetty 缺 SLF4J impl 时退化为 NOP logger，不影响功能）。
   - 修改：`build.gradle` 删 `implementation("org.slf4j:slf4j-simple:...")` 这行（**已经做了，去 build.gradle 看**）
   - 保留 `implementation("io.javalin:javalin:${javalin_version}")`
2. **JDK 21**：CI runner 默认 JDK 17，**已有 setup-java step**（参照 CLAUDE.md §3）——别碰 build.yml。
3. **Javalin 6 在 MC classpath**：Javalin 内部用 Jetty + websocket-api，可能与 MC 的 `com.sun.net.httpserver` 冲突。**首次 build 后跑 `gradle runClient` 测试**——如果 startup crash，备选方案是换 **NanoHTTPD**（更轻量，不引入 Jetty）。决策点留给 CC 自己判断。
4. **DisplaySource 注册方式**：参照 `BoilerDisplaySource` / `ComputerDisplaySource` 在 Create `content/redstone/displayLink/source/` 的实现。**务必用 `codegraph_search "DisplaySource"` 和 `codegraph_explore BoilerDisplaySource.java`** 摸清 API，不要凭记忆写。
5. **gradle.properties 是 single source of truth**：mod_id, mod_name 等都在那里——不要在 build.gradle 里硬编码。

## 必须修改的文件

```
build.gradle                       # Hermes 已删 slf4j-simple；保留 javalin
src/main/java/com/example/webboard/CreateWebBoard.java       # 已有 @Mod 入口，需要加 Registrate 初始化（如果还没有）
src/main/java/com/example/webboard/content/displaysource/WebDisplaySource.java   # 新增
src/main/resources/META-INF/neoforge.mods.toml              # 已有，可能要加 deps
```

## 测试任务（CC 必须做）

1. **单元测试**（如有意义）：纯 Java 测试
2. **本地 build 验证**：`gradle build --no-daemon --stacktrace` 必绿
3. **MC 启动验证**：`gradle runClient`（如果沙箱有 GPU / 跑得起来；无 GPU 可跳过这条但要在 commit message 里写明）
4. **回填 commit message**：每个 commit 必须有"why"段，写明踩了什么坑、查了什么 codegraph

## 验收 checklist（CC 完成时自检）

- [ ] 本地 `gradle build` 绿
- [ ] GitHub push 触发 CI 绿
- [ ] 三个新 java 文件 + 不超过 3 个 modified 文件（diff 干净）
- [ ] commit message 用 imperative + body 解释 why
- [ ] 没引入新依赖冲突（gradle dependencyInsight 干净）

## Done 定义

PR 合并到 main → CI 绿 → Hermes 收到通知 → 用户看到 PM 汇报。

---

## 派活 prompt（复制给 CC CLI）

```
You are working on create_web_board mod at ~/Project/MC_MOD_DEV/create-web-board/.
This is issue #1 — scaffold + register a custom DisplaySource.

CONTEXT:
- Create 6.0.10 source indexed at ~/Project/MC_MOD_DEV/.codegraph/create/ (HEAD ac0c444d9)
- NeoForge 1.21.1 source indexed at ~/Project/MC_MOD_DEV/.codegraph/neoforge/ (HEAD 2f185f98)
- Use codegraph_search / codegraph_node / codegraph_explore before writing any API code.
- Engineering workflow: receive task → tdd (write failing test → green → refactor).
- CLAUDE.md in repo root has full workflow + pitfall notes. Read it first.

GOAL:
1. Register a WebDisplaySource class that implements Create's DisplaySource interface.
   Reference: BoilerDisplaySource / ComputerDisplaySource at com/simibubi/create/content/redstone/displayLink/source/
2. For #1 scope: source doesn't need to do anything beyond implementing the interface
   and registering via CreateRegistrate.displaySource(...). NO HTTP server yet (#2).
3. Make gradle build PASS locally and CI green.

KNOWN PITFALLS (from Hermes):
1. SLF4J version conflict — REMOVE the slf4j-simple dep line in build.gradle.
2. DisplaySource registration must use CreateRegistrate.displaySource("name", Supplier) helper.
3. The "Web Board" source name needs to be unique across all sources.

DELIVERABLES:
- A new branch feature/issue-1-scaffold with one or more commits
- gradle build green locally (run it yourself before pushing)
- A PR (or push to main if user prefers trunk-based — check CLAUDE.md git workflow section)
- Commit message that explains the WHY of each non-obvious choice

WHEN DONE: report back with build status, files changed, and any new pitfalls discovered.
```