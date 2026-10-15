# StellarCraft：EVE 模式切图／切回 EVE 崩溃专项审查及修复计划

最新修正：当前 World 查询的 ObjectProperty 数组须先解包 RemoteUnrealParam；此前遗漏导致敌人登记为零、无法伤害。已修正并仅替换 main.lua，见 [伤害回归记录](actor-query-unpack-20261014/DIAGNOSIS.md)。伤害实机恢复仍待确认，当前哈希以该目录 manifest 为准。

当前用户反馈（2026-10-14）：用户确认当前复测不再崩溃，保留该验收结果，不主动重开旧崩溃。现任务为 EVE 模式移动卡顿；已更换当前 World 角色查询以移除周期全局扫描，见 [EVE 卡顿记录](eve-stutter-20261014/DIAGNOSIS.md)，新观感仍待验证。当前 main.lua 哈希以该目录 manifest 为准。

当前 Lua 后续修正：低帧率日志暴露每帧重复移动接管和全局控制器扫描回归，已修正并仅更换 main.lua；见 [性能回归记录](perf-wrapper-20261014/DIAGNOSIS.md)。切图清空保持，新 FPS 仍待实测。

## 用户变更：切图清空旧 MC 内容（2026-10-14，当前）

已按用户后续要求，将下节原“新世界重同步旧账本”改为“地图名称变化后清空旧 MC 内容”：登记方块账本/坐标偏移保存为空，服务端移除登记方块和旧代次实体；旧存档实体标签和渲染快照不能再进入新地图。F4、同地图重绑保留。新增独立稳定 mapId（原保留字段 0x3C），不把带 epoch 的 levelId 变化误当切图。DLL+Lua+JAR 已备份安装，当前哈希/离线证据和范围详见 [清空实现记录](map-clear-20261014/IMPLEMENTATION.md) 与 `map-clear-20261014/manifest.json`。当前仍暂停实机测试；下节原部署哈希已被本次替代，两类原崩溃仍未实机验收。

## 当前实施状态（2026-10-14）

**生命周期修复候选已实现、离线通过并备份部署；两类原始崩溃均未实机验收。** 本轮遵守游戏测试暂停要求，没有启动游戏、发送输入或写 QA 指令。轻击验收和当前重受击/Timed HitStun 任务状态保留，未放开任何伤害或 ABI 门禁。

- 新增 `world_lifecycle.lua`：卸载递增 epoch、清空上下文和 levelId、拒绝旧 Tick/F4；每个 epoch 独立记录待执行 Tick，旧闭包完成或丢失均不会解锁/堵住新世界任务。
- 匹配 UE4SS 本机版本 `d3d1004` 的源码已保存到 `docs/map-travel-20261014/LuaMod-d3d1004.cpp`。本版 `LoadMap` 循环内部提前 return，只执行第一个模组。安装目录中 BPModLoader 只注册 PostHook，没有其他 Lua 模组注册 PreHook；因此用 `RegisterLoadMapPreHook` 停机、`RegisterInitGameStatePostHook` 提供新世界身份，不依赖无法送达的 PostHook。补充玩家 EndPlay、ClientRestart 和 QuitGame 屏障。注册失败时不启动世界工作。静态 API/顺序证据不是本机实测回调覆盖率；后续安装其他 PreHook 模组时必须重新核查顺序。
- 新上下文仅从当前注册表取得本地 Controller/Pawn/World，核对新世界地址和 Eve 类；主循环绕开 UEHelpers 的跨地图 Controller 缓存。地图名称只在重绑时读取，运行帧复用标量身份。敌人不再持有两秒 Actor 缓存；目标/来源表改为地址、GUID、类型、epoch 标量，使用前从新注册表复核，避免先对旧 Actor 调用 IsValid。
- `forget_world` 及实体、灯光、粒子、手/身体、MID/Texture 缓存清除只操作 Lua 表，不访问旧 UObject。磁盘模型数据保留。同一活世界的显式清除仍走原有 reset/Destroy 路径；不因新地图出现而销毁旧世界对象。本轮卸载路径保守采用 forget，由引擎负责 World 对象销毁，未声称验证了预卸载逐 Actor 销毁。
- DLL 新增 `sc_world_barrier_lua`，只撤销自有保护租约并丢弃命中队列，不读取/调用引擎对象；在途工作线程命中带内部 generation，不能在重绑后补入旧命中。
- 保持共享内存布局和协议 v6。`SbState.levelId` 变为包含本地 epoch 的运行期世界身份，原点磁盘文件仍按原地图名称保存。类型 3/5/7/8 的原未用 weapon 字段携带世界标识；战斗事件的 v6 weapon/跳劈位不变。旧标识事件直接丢弃。碰撞反馈文件升级 `SCFEED3` 并验证同一标识；延迟珍珠/爆炸发布保留捕获标识。MC 方块同步键加入原生世界身份，同 PID/同 MC 世界切换地图后也会重同步。**DLL、五份 Lua 和 JAR 必须成套更换；不支持只更换其中一部分。** addon 无协议变更，本轮未更换。

证据目录：`docs/map-travel-20261014/`。两份原始 dmp/xml 与 UE4SS 日志已独立复制；原目录未改动。`dump-module-match.json` 证明两份转储的 exe/UE4SS 镜像大小、时间戳及 RSDS GUID+age 均与本机二进制匹配。`crash-disassembly.txt` 确认 A 类 `UE4SS+71B7DF` 是对象索引相关读取，B 类 `SB+29B6A27` 是 `mov byte ptr [rdx+39],r8b`；B 类 RDX 为零的来源及其对应 Lua 调用仍未定位，不能合并两类根因或声称它们已消失。

离线结果：全部 Lua 语法；真实 main 的生命周期/重复卸载/同地址同地图重入/旧闭包/EndPlay 重绑/缺导出拒绝；毒化旧对象下 World/粒子/身体 forget 零访问；旧世界事件/已销毁敌人拒绝及现有唯一 HP、箭矢/TNT/跳劈样式；运动、台阶、身体、姿态模型和方块队列/网格回归通过。DLL/JAR 构建及 remap 通过；557/2989 处原生门禁字节变异拒绝；实际 Java 世界事件发布、命中包和宿主跳劈；实际 v6 DLL/Lua 地图发布/失效、事件和 17 项运动/姿态检查通过。旧方块队列测试缺少已校准原点，已补齐替身并增加未校准时保留队列的断言，未为通过测试改生产队列预算。

安装前确认剑星及 Java 均未运行、QA 文件 0 字节；六份旧组件备份至 `before-install/`，新增一份生命周期 Lua，总计七份候选及部署文件 SHA256 一致，见 `manifest.json`：

- DLL：`52BA1646BAA2DC1FF9E3252796FD56BAB4A285A0A85297B47AC40DF369BEDEEE`
- main.lua：`D05D227A045BC53059CA851BA035C75AB2AD47CE169CA7EB6B892CE221F3615D`
- JAR：`98125B0C4BD4B4AD50C73F39F064A850EAA452A7872AA94E2A246993FF57DC8F`

下一步在用户明确恢复测试后，由用户普通启动/操作，先确认 Lifecycle 的 LoadMap pre→新 epoch ACTIVE 以及 EndPlay/ClientRestart 日志，再多次验证原失败切图、MC→EVE、退出路径及新地图相机/控制/方块重建。保持首领模式暂停。不以离线或单次进图成功替代这两类崩溃的实机验收；如果 B 类再次出现，按原偏移和新阶段日志独立定位参数。

以下为 **2026-10-13 原始只读审查与拟议方案**；旧协议 v5、行号和“尚未实施”仅描述当时版本，以本节和 manifest 为当前状态。

> 审查时间：2026-10-13，北京时间（UTC+8）  
> 范围：最新 minidump、CrashContext.runtime-xml、UE4SS.log 与当前工作树源码  
> 状态：**只读审查；未改业务源码、未构建、未部署、未启动游戏、未实机验收**。

## 一、核心结论

最新两次崩溃是**两种不同的直接故障签名**。优先排查 StellarCraft 的 World 生命周期：即使切到 EVE，Lua 主循环仍在反复枚举原生 Actor、读取 UObject、消费事件和更新方块/实体。当前没有可靠的“卸载前停机＋旧 World 代次失效＋新 World 重绑”统一屏障。**这是一项高优先级、尚待验证的根因假设，不是已证实的直接崩溃函数。**

不能靠永久关闭 EVE 模式的全部 MC 联动规避问题；未来的 EVE/MC 生物联动也需要安全的世界对象生命周期。

## 二、最新两份原生崩溃

| 本地时间 | 故障模块与偏移 | 异常 | CrashContext 关键栈 |
|---|---|---|---|
| 2026-10-13 **22:38:18** | `UE4SS.dll+0x71B7DF` | `0xC0000005`，读 `0x00000178EEEF6E0C` | `UE4SS+0x71B7DF → +0x4ADB0F → +0xC62DE4 → ...` |
| 2026-10-13 **22:29:42** | `SB-Win64-Shipping.exe+0x29B6A27` | `0xC0000005`，写 `0x39` | `SB.exe+0x29B6A27 → +0x29BADB8 → ... → UE4SS+0x7818B9` |

原始目录，**不要修改或覆盖**：
- `C:\Users\StickySlime\AppData\Local\SB\Saved\Crashes\UE4CC-Windows-9C5238B7422F167EF5952AA757664457_0000\`
- `C:\Users\StickySlime\AppData\Local\SB\Saved\Crashes\UE4CC-Windows-CDCBF20B43970FCA8FBFD4983F24B8C4_0000\`

每个目录有 `UE4Minidump.dmp` 和 `CrashContext.runtime-xml`。UE4SS 故障 `+0x71B7DF` 也曾在 10-11 17:48、18:01、18:18、20:35，以及 10-12 01:11、21:04 出现；**重复偏移强烈提示共同访问路径，不代表所有触发条件相同**。

22:29 的游戏 exe 故障为近 NULL 写入，栈底包含 UE4SS；应单独调查 Lua→游戏函数的参数和对象身份，不能草率归因于游戏本体或 GPU。尚无 PDB/精确符号证据可把任一故障对应到特定 Lua 行。

### 最新 UE4SS 日志时序

文件：`D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\ue4ss\UE4SS.log`。**日志时间为 UTC，换算北京时间加 8 小时。**

- `14:37:21`：已进入 `E05_GreatDesert_P`。
- `14:37:27`：F4→STEVE；`14:37:34` 开始生成 MC 方块、相机、身体/手部和灯光。
- `14:38:06.595`：`F4 pressed; control=EVE`。
- `14:38:06.600–14:38:06.603`：PlayerGuard 关闭；原生 `CH_P_EVE_01_Blueprint_C` ViewTarget 恢复；EVE 角色显示。
- `14:38:07.654`：EVE profile 仍包含 `mirror`、`publish`。
- `14:38:10.307`：`[StellarCraft-Actors] candidates=88 nearby=5 published=5 rejection=nil`。
- `22:38:18` 本地时间：新的 UE4SS 访问冲突。

**证据边界：**日志证明“MC→EVE 后世界扫描仍在运行”，但没有明确记录 14:38:10 后的另一张地图开始 LoadMap，因此最新这一例不应被描述为已证明“切图瞬间崩溃”；应同时覆盖 MC→EVE 后残留对象和地图卸载两条路径。

## 三、当前工作树审查

审查时文件 SHA-256：
- `sb-mod/lua/main.lua`：`A25DAF38A15E8FF92E21B08EDDD3BB4EAE72EE402AC6514D7DCE567A56B8A338`。
- `sb-mod/lua/native_world.lua`：`7C8EA5CDBECE311A082769E58FC28C2E82E212CED839C3A6501A0FD15F8FB99A`。
- `sb-mod/lua/block_visuals.lua`：`8C9B18A38722DB2181F6C06BBCB5614873C3718AA5819C5DB6B1DC15D8768709`。

仓库已有大量未提交变更，特别是普通敌人受击动画、命中上下文和协议 v5；**禁止清空 Git 工作树、还原文件或覆盖其他任务的改动**。`sb-mod/src/stellar_blade_mod.cpp` 是旧 skeleton，并非当前 Lua 生产主循环；`shm_bridge.cpp` 是共享内存/原生桥。

| 优先级 | 位置（当前版本） | 代码事实 | 风险 |
|---|---|---|---|
| **P0** | `main.lua:2010–2129` | 无论 EVE/STEVE，`tick` 仍执行 `publish_player_frame`、`mirror_actors`、`consume_native_hits`、`apply_events`、`drain_block_queue`、`NativeWorld.tick` 等 | 世界卸载时继续访问 UObject |
| **P0** | `main.lua:2172–2182` | `LoopAsync(8)` + `ExecuteInGameThread` 只用 `tickPending` | 已排队旧任务没有世界代次检查；GameThread 也不能保证 World 未卸载 |
| **P0** | `main.lua:516–541, 554–695` | 从 `PersistentLevel.Actors` 或 `FindAllOf("Character")` 枚举；`gActorCandidates` 缓存 Actor，最多隔 2 秒刷新，以 World **地址**区分 | 缓存对象可能被销毁／地址复用；`IsValid` 也是原生访问 |
| **P0** | `main.lua:340, 555–584, 624–687, 766–805` | `gActorsByName`、`gCombatSources` 保存 Actor；异步命中/伤害事件消费 | 旧世界事件串入新世界 |
| **P0** | `main.lua:141–151, 153–209` | 发布每帧时读取 `pawn:GetWorld():IsValid():GetFullName()` 计算 `native_level_id` | 切图中为了检测世界而访问正在销毁的世界 |
| **P1** | `main.lua:1048–1056, 1190–1205` | 保存、恢复 CameraController / ViewTarget / MC Camera | F4 与切图交叠时旧 ViewTarget 失效 |
| **P1** | `main.lua:23–63, 1863–1917` | `gBlockActors`、`gGroundIgnorePawn`、`gBlockQueue`、`gFrameOff`、`gBuildWorldId` | 没有 SB 原生世界代次，MC 维度 ID 不是 UE World 生命周期 |
| **P1** | `native_world.lua:14–29, 49–52, 99–109` | 通过 World 地址/MC 构建世界变化才 `clear()`，清理中 `IsValid`/`K2_DestroyActor` | 等发现新 World 时旧 Actor 可能已无效，清理本身可崩 |
| **P1** | `block_visuals.lua:11–70, 172–181, 264–294` | 按文件名缓存 Dynamic Material/Texture，手/身体按 `ownerAddress` 缓存组件 | 世界绑定资源跨地图复用，地址复用和延迟销毁风险 |

`pcall` 只捕获 Lua 层可抛出的异常，不能捕获 C++ 原生访问冲突。`gBuildWorldId`（MC 维度/构建语义）不能作为 SB 的 `worldEpoch`；当前地图名称哈希也不能阻止在 `GetFullName` 之前的野指针访问。

## 四、分阶段修复方案

### P0：保留证据、先定位实际触发链

1. 先复制两份新 minidump/xml 和最新 UE4SS.log 到独立只读证据目录，并记录部署 DLL/Lua 的哈希，禁止用另一版本二进制分析偏移。
2. 对**匹配本机版本** `UE4SS+0x71B7DF` 做符号或反汇编定位；对 `SB.exe+0x29B6A27` 独立确认函数、调用参数与空指针来源。
3. 加低侵入 breadcrumb，记录 `phase`、`worldEpoch`、模式、队列大小、`tick` 的 `mirror/events/blocks/nativeWorld/ground` 阶段进入/离开。日志只记录事先保存的标量，不在日志里触发 `GetFullName` 或 `IsValid`。
4. 后续由用户自主操作普通区域时，才做逐模块**临时隔离**：敌人扫描、事件、原生实体、方块/材质、相机恢复；对比 A/B 崩溃签名。不永久删除 EVE 联动。项目加载了其他 UE4SS 模组的可能性也需对照实验，不预断罪责。

### P0：增加真实 World 生命周期屏障

实现 `NO_WORLD → ACTIVE → UNLOADING → WAITING_NEW_WORLD → REBINDING → ACTIVE`，维护单调递增的 `worldEpoch`。

- **优先验证当前 UE4SS 版本真实提供的“World 还活着”的预卸载事件、地图切换事件及其调用线程与顺序**；不得编造未验证的回调 API，也不能仅靠下一帧检测到新地址才清理。
- 收到可靠预卸载通知：立即设 `UNLOADING`、递增 epoch、禁止新 UObject 访问/生成、暂停事件消费并发布 `IN_GAME=0`、`hostLevel=0`、空原生目标表。
- **旧 World 尚有效时**，在游戏线程安全销毁自建方块、实体、光源、粒子、相机、组件并恢复需要恢复的本世界输入。之后仅清除 Lua 引用。
- 如果通知来得太晚或无法证明旧 World 有效，**只能 forget，不能再调用旧 Actor 的 `IsValid/Destroy`**；延迟销毁不能被假定安全。
- `ExecuteInGameThread` 的闭包记录排队 epoch。执行前要求 `phase==ACTIVE` 且 epoch 相同；所有世界/实体操作都必须过门禁。仅通过线程检查或 Lua `pcall` 不算通过。

**设计伪代码（接口均为拟议名称，不代表已确认的 UE4SS API）：**

```text
on_pre_unload_confirmed:
    phase = UNLOADING
    worldEpoch += 1
    bridge.publish_inactive_and_clear_targets()
    reject_queued_events()
    dispose_live_world_if_still_safe()
    forget_all_world_scoped_references()
    phase = WAITING_NEW_WORLD

game_thread_task(capturedEpoch):
    if phase != ACTIVE or capturedEpoch != worldEpoch: return
    run_world_work_for_current_context()

on_new_world_ready_confirmed:
    phase = REBINDING
    verify_fresh_world_pawn_controller_and_components()
    initialize_new_world_scoped_resources()
    phase = ACTIVE
```

如果找不到**确实早于 UObject 被释放**的事件，不能把简单的 `pawn:IsValid` 或轮询 `GetWorld` 宣称为完全消除崩溃；必须先补准确观测再实施安全清理。

### P0：把世界资源分为“安全销毁”和“仅遗忘”两个路径

改造 `main.lua`、`native_world.lua`、`block_visuals.lua`：

- `dispose_live_world(epoch)`：仅在验证世界有效且线程正确时，对本世界自建 Actor/组件执行卸载。
- `forget_dead_world(epoch)`：清空 Lua/标量数据，不访问任何旧 UObject 属性、方法、`IsValid` 或 UE4SS 反射。
- 清理 `gActorCandidates/gActorWorld/gActorsByName/gCombatSources`、`gGroundIgnore/gGroundIgnorePawn`、`gCapsulePawn`、`gBlockQueue`、`gFrameOff` 的原生世界绑定状态；恢复或遗忘相机/输入；重置 NativeWorld 的 entities/lights/fx/particlePool；失效 BlockVisuals 的 hands/body、动态材质和纹理缓存。
- 磁盘解析出的静态 mesh 数据可以保留；**由 World 创建的 MID、Texture、Actor、Component 都不得跨世界复用**。
- 防地址复用：不能仅比较 `GetAddress()` 或 Pawn userdata 相等；每份缓存携带 epoch。

### P1：新地图重绑和协议防串图

1. 单独记录 `sbWorldEpoch/sbLevelId` 与 `mcDimensionId/gBuildWorldId`；所有队列条目、伤害、代理表需能识别其所属原生世界代次；过期事件只丢弃，**不补打伤害**。
2. 地图加载和失效时发布 `IN_GAME=0`、`hostLevel=0`；安全重绑新 Pawn/Controller/World 后才恢复发布。不要在世界未被确认有效时为获取 levelId 而调用 `GetFullName`。
3. F4→EVE：只在同代次有效对象上恢复相机、输入和可见性；如已卸载则直接遗忘旧 Camera/ViewTarget，交由新 World 的原生默认设置。
4. 不改变已验收的伤害比例、目标 GUID/ABI/线程/时效门禁，不顺便修改受击动画 Show 入口或 MC 原版 AI；协议 v5 的修改必须保持相容，避免混装。
5. `SB.exe+0x29B6A27` 若在上述保护后依然复现，依据反汇编及 CrashContext 中 UE4SS→游戏函数的栈，进一步排查具体的无效调用参数；**不可强行认为与 A 类同根**。

## 五、建议交付文件与验收

| 文件 | 修改目的 |
|---|---|
| `sb-mod/lua/main.lua` | World phase/epoch、调度门禁、敌人缓存/事件隔离、F4 及重绑 |
| `sb-mod/lua/native_world.lua` | 活世界销毁、死世界仅遗忘、实体/粒子/灯光按代次重建 |
| `sb-mod/lua/block_visuals.lua` | 手/身体/组件状态与世界资源缓存清理 |
| `sb-mod/src/shm_bridge.cpp`（如确有需要） | 无效世界发布清零和代次入站门禁；ABI 与 Lua 保持一致 |
| 新增离线回归 | 状态机、重复卸载、同地址复用、迟到 Tick/事件、无效世界“零原生调用” |

**离线检查（不启动游戏）**：状态转换可重入、旧 epoch Tick 拒绝、卸载后 Actor/Material 不能复用、队列迟到事件丢弃、空世界 publish 清零、协议 v5/现有伤害门禁与 Lua 语法回归均通过。

**未来由用户自行实机验收**（本轮不启动或发送游戏输入）：
1. EVE 希雍↔大沙漠，观察正常切图与地图加载。
2. MC 已载入且场景有方块/实体时，MC→EVE→切图；新地图不残留旧 Actor、贴图、光源或事件。
3. 多次 EVE↔MC 与正常退出，不再出现两种崩溃签名；每次分别保存日志、dmp、部署哈希。
4. 新地图下原生相机、EVE 控制、MC 方块、原版伤害安全门禁保持正常。首领模式不测试。
5. **至少多次稳定复现原失败场景的无崩溃结果**才能标为实机通过；仅编译或单次进图成功不能称修复完成。

## 六、当前结论／执行顺序

**先做最小诊断、再实现预卸载生命周期门禁、再集中清理对象引用、最后重绑新世界与回归。** 目前只有只读分析和可实施修复计划；没有证明 22:38 的错误唯一来自切图，也没有把两种崩溃混为同一原因。已存在的 Git 未提交修改全部保留。
