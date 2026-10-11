# 在《剑星》中直接操控 MC 人物 —— 设计方案

> **本文档回答的问题**：如何在《剑星》（Stellar Blade, UE4.26 / DX12）里"直接操控一个 MC 人物"，
> 而不是仅仅隐藏 Eve 的模型。
>
> **前提约束（用户明确要求）**：**玩家所有的操作都在《剑星》窗口内完成。** MC 窗口只是渲染源，
> 可以在后台、可以是最小化的、可以不显示任何玩家需要看的东西。
>
> **参考实现**：`reference/SekiroCraft-Passthrough`（只狼）、`reference/universal-modder`
> （`examples/minecraft-gta5-passthrough`，GTA5）。两者均已在本地克隆，可直接读源码。

### 当前实现状态（截至 2026-10-08 22:10）

| 验收标准 | 状态 |
|---|---|
| A1 F4 立即切第一人称 | ✅ 已验证（`UE4SS.log` + MC 日志双侧确认，来回切换正常） |
| A2 MC 运动方式 | ⚠️ 代码就绪，**未完整实测** |
| A3 剑星画面里显示 MC HUD | ⚠️ **显示链路已验证**（`overlay.log` 有 `SHOW`、帧在消费）；**帧内容刚修好，待实测** |
| A4 第三人称 Steve | ⚠️ 同上（本轮顺带修好了一个"Steve 一直被擦掉"的旧 bug） |
| A5 全程在剑星窗口内操作 | ✅ 已实现（overlay 持有全部输入钩子）；**A3 通过后即成立** |
| A6 剑星菜单可用 | ✅ 已验证（按钮可点） |
| A7 退出回安全态 | ✅ 已验证（overlay 自行退出） |

> **⚠️ 已知未收尾**：`render/FrameExporter.java` 里还留着本轮排障用的临时诊断
> （`describeState` / `crossCheckReadPixels` / 日志节奏）。**A3 实机确认通过后应清掉**，
> 恢复干净构建。

---

## 0. 验收标准（先把它钉死，否则"实现了"没有判据）

| # | 标准 | 判据（可观测） |
|---|---|---|
| A1 | 在**剑星窗口内**按 F4，立即切到 MC 第一人称 | 不需要 MC 进入存档；剑星画面出现 MC 准星/工具栏 |
| A2 | 移动沿用 **MC 的运动方式** | WASD/跳跃/疾跑/潜行/游泳/飞行全部由 MC 原版逻辑处理，前馈给剑星 |
| A3 | 剑星画面里能看到 **MC 的 HUD 与界面** | 工具栏、血量、背包、物品栏都可交互 |
| A4 | 第三人称（F5）能看到 **Steve 的模型** | 剑星画面里出现 Steve 身体 |
| A5 | **全程只在剑星窗口里操作** | 鼠标/键盘焦点不需要离开剑星窗口 |
| A6 | **剑星自己的菜单仍然可用** | 剑星主菜单/暂停菜单按钮可点，不被 MC 吞掉 |
| A7 | 任一侧进程退出 → 另一侧回安全态 | overlay 自行退出；剑星恢复 Eve；MC 回 EVE |

> **A5/A6 是这套方案里最容易做错的两条**，也是与"仅隐藏宿主模型"那种简化做法真正的分界线。
> 下文 §3、§6 有专门的机制与踩坑记录。

---

## 1. 三个参考方案的取舍（为什么不能照搬任何一家）

### 1.1 GTA5 passthrough —— **架构相反，不能照搬**

| 维度 | GTA5 示例 | 本项目 |
|---|---|---|
| **相机权威** | **宿主持有**：GTA 每帧把它的相机发给 MC，MC 侧 `CameraMixin` 在 `Camera.update → alignWithEntity` 的 TAIL 直接覆写 `xRot/yRot/rotation`，连 roll 都接管；还接管 `calculateFov` 与裁剪面 | **MC 持有**：MC 拥有玩家与相机，剑星只跟随（用 `teleportSeq` 单次传送同步） |
| 角色权威 | 宿主 | MC |
| 例外支线 | `g_drive`（飞行）时反过来：MC 算物理、宿主跟随 | — |

**结论**：它的默认模式与需求**正好相反**（"MC 只是跟随宿主"）。它的 `REFERENCE_DESIGN.md` 也只狼
那份文档里被点名："其默认行走仍由 GTA 主导……**不能照搬来满足本项目的 MC 操作要求**"。

**但有三块值得借鉴**（已在本项目落地/排队）：
1. **离线自测**：`mc/.../fakehost` 思路 + `gta/tests/fakegta.cpp`（把真合成器 + 真 WebSocket 一起编译）。
   原话："让大部分工作在两款游戏都没装好的时候就完成了。" → 我们做了 `tools/probe_shm.py` /
   `test_pose_pipeline.py` / `test_damage_conversion.py` 这一组。
2. **重投影掩盖延迟**：把"这一帧用什么位姿渲染的"随帧发出去，宿主侧重投到当前相机。
   → 我们协议 v2 已把 `camX/Y/Z + yaw/pitch/roll + vFov + aspect + near/far + cameraMode` 放进 slot 头。
3. **槽描述符的 flags 命名**：`zZeroToOne` / `bottomUp` / `reversedZ` —— 踩坑后定的名字，可直接沿用。
   → 我们的 `OverlaySlotFlags` 已含 `bottomUp` / `poseValid` / `hasDepth` / `depthLinear` / `depthReversed`。

引以为戒：它的模式切换是**单开关 F7**，且关闭时"关掉整层"（`show_player` + `props_clear_all` +
排空 WebSocket 积压）。单开关够用是因为它**没有"宿主菜单要能用"这条约束**——代价是作者自述
21 条 gotcha 里有一条至今未修（暂停菜单会停掉 ScriptHookV 脚本，屏幕留着最后一帧 MC 画面）。

### 1.2 SekiroCraft-Passthrough（只狼）—— **架构同源，骨架照抄**

| 维度 | 只狼 | 本项目 | 是否照搬 |
|---|---|---|---|
| 权威拆分 | 「谁驱动角色」与「谁收输入」两个独立概念，宿主是唯一权威 | 同 | ✅ 照搬 |
| 输入接管 | 宿主侧钩子读原始输入并**吞掉**，经队列转发给 MC 回放 | 同（`InputRing` + `KeyMapping` 回放） | ✅ 照搬 |
| 画面 | 世界/深度/HUD **分层**导出，HUD 叠在宿主画面上 | 同（但合成通道换掉，见下） | ✅ 概念照搬 |
| 地形 | 玩家附近固定格采样地面高度，给原版放置射线虚拟顶面 | 9×9 / 0.5 m 格 / 半径 4 m / ≈10 Hz | ✅ 照搬 |
| 战斗 | 不可见 MC 代理实体接收原版伤害，再翻译成宿主伤害 | 同 | ✅ 照搬 |
| 通道 | Win32 共享内存 + 有序输入事件环 + 心跳 | 同 | ✅ 照搬 |
| **注入通道** | `dinput8.dll` 代理；**无引擎反射**，靠**离线反汇编**确认 HP setter ABI | UE4SS + `stellarcraft_shm.dll` + Lua，走 **UE 反射** | ❌ 换 |
| **画面合成** | hook **D3D11 命令列表**，按实际 command list **关联原生深度** | 宿主是 **DX12** → 独立进程 `overlay.exe` + GDI 分层窗口 | ❌ 换 |
| 进程拓扑 | 2 个 | **3 个** | ❌ 换 |
| MC 侧共享内存绑定 | **JNI**，jar 内含原生 DLL | **纯 Java FFM**（`NativeLink` 绑 `kernel32`），jar 内无 DLL | ❌ 换 |

**换掉的四条里，只有第一条是"选择"，后三条是被宿主逼的**：剑星是 DX12/UE4，
进程内 hook 的代价远高于只狼那种 D3D11 非 UE 引擎。

### 1.3 结论

> **架构骨架照抄只狼，四条落地路径因宿主不同全部重写。**

---

## 2. 总体架构

### 2.1 核心洞察：把「角色归属」与「输入归属」拆成两个独立概念

这是整套方案最重要的一条结构决策（写在 `protocol/stellarcraft_protocol.h` 的 AUTHORITY MODEL 段）。
**宿主机是权威**，但"谁驱动角色"与"谁接收输入"是两件事：

| 概念 | 协议位 | 谁写 | 含义 |
|---|---|---|---|
| **角色归属** | `SbFlags.CONTROL_STEVE = 0x10` | F4 热键（电平锁存） | 谁是当前化身。决定 overlay 是否显示、Eve 是否隐藏、MC 是否画 Steve 身体。**任何时刻有效，含 MC 在主菜单** |
| **输入归属** | `SbFlags.EDIT = 0x20` | **只有 overlay** | 剑星的按键是否被吞掉并转发给 MC |
| **宿主原生 UI** | `SbFlags.NATIVE_UI = 0x40` | **只有 overlay** | 剑星自己的菜单在收输入，MC 必须放手并松开所有按键 |
| **MC 就绪位** | `McFlags.CONTROL_STEVE = 0x100` | MC 自己 | "我能收输入了" |

```
MC 侧门控： Proto.mayEdit(f) = (f & EDIT) && ((f & (EDIT|MENU_OPEN|NATIVE_UI)) == EDIT)
overlay 输入门： should_forward() = F4锁存 && MC就绪位 && game_foreground()
overlay 显示门： positioned && is_steve_mode() && game_foreground() && !sb_minimized()
```

**两个门绝不可混用。** 显示门是纯显示（与输入权威无关），输入门才决定吞不吞剑星的键。
历史教训写进了 `MEMORY.md` §一：曾把两者塞进同一个位，导致"剑星主界面按钮全点不了"；
随后加 `IN_WORLD` 门补救，又变成"必须先进存档才能按 F4"。**两个方向都错，别再试。**

位所有权纪律：`0x0F` 状态位 → Lua；`0x10` 锁存 → F4；`0x20/0x40` → **只有 overlay**
（`shm_bridge.cpp` 的 `sc_write_player` 必须 preserve `0x70`），因为只有它知道窗口焦点。

### 2.2 三进程拓扑

```
┌──────────────────────────────┐        ┌──────────────────────────────┐
│ SB-Win64-Shipping.exe        │        │ overlay.exe                  │
│  ├ UE4SS                     │        │  ├ WH_KEYBOARD_LL / MOUSE_LL │
│  │   ├ stellarcraft_shm.dll  │        │  │   （唯一拿到原始输入的人） │
│  │   └ Scripts/main.lua      │        │  ├ 满屏 WS_EX_LAYERED 窗口    │
│  └ 游戏本体（权威宿主）       │        │  └ UpdateLayeredWindow 叠图   │
└──────────┬───────────────────┘        └──────────┬───────────────────┘
           │                                        │
           └──────────┬─────────────────────────────┘
                      ▼
      Local\StellarCraft_v1   共享内存，286 MiB（协议 v2）
                      ▲
                      │  纯 Java FFM（NativeLink 绑 kernel32，jar 内无 DLL）
           ┌──────────┴───────────────────┐
           │ java.exe（Minecraft Fabric） │
           │  └ 拥有玩家、相机、物理、物品 │
           └──────────────────────────────┘
```

**为什么 overlay 必须是独立进程**：它是唯一持有输入钩子、知道窗口焦点的角色；
把它从宿主进程里拆出来后，**MC→剑星方向的画面合成完全不碰剑星进程**（无注入、无 hook、无完整性风险）。

**代价（必须承认）**：
1. 进程外叠窗**拿不到宿主的深度缓冲** → 目前只叠图，不做遮挡（详见 §6-D）。
2. 顶层窗口的 z-order / 前台归属**得自己管** → 这是本项目踩坑最密集的一块（§6-4、§6-5）。

### 2.3 共享内存布局（协议 v2，`kMappingBytes = 299 888 768` ≈ 286 MiB）

| 区域 | 偏移 | 说明 |
|---|---|---|
| Header | `0x0` | magic `STAC` / version / 两侧 pid / 心跳 |
| SbState | `0x100` | 剑星状态 + 权威位 |
| McState | `0x200` | MC 状态 + 位姿 + teleport ack |
| ActorTable | `0x300` | 头 + 64 B × 256 条 actor 记录 |
| InputRing | `0x1000` | SPSC 字节环，12 B 事件 `{u16 type, u16 code, i32 a, i32 b}` |
| EventRing | `0x10000` | MC→SB，32 B `McEvent` |
| CollisionRing | `0x20000` | 32 MiB |
| OverlayCtl | `0x2020000` | 三缓冲 slot 头（各 `0x80`）+ 帧计数 |
| OverlayPixels | `0x2021000` | 3 × 3840×2160×4 |
| OverlayDepth | — | **94.9 MiB 已预留，未产出** |
| RenderRing | — | 64 MiB |

`OverlaySlotHdr` 从 `0x40` 扩到 `0x80` 是协议 v2 的改动，新增**帧位姿**
（`camX/Y/Z` double + `camYaw/camPitch/camRoll` float + `vFovDeg` / `aspect` / `nearZ` / `farZ` +
`cameraMode` u32）+ **深度层描述符**（`depthOffset` / `depthBytes`）。
**`kOffOverlayCtl` 与 `kOffOverlayPixels` 一个字节都没动** → overlay 的显示逻辑零改动。

---

## 3. 模块划分

### 3.1 MC 侧（Fabric mod → `stellarcraft-0.1.0.jar`）

| 模块 | 文件 | 职责 |
|---|---|---|
| 协议绑定 | `link/Proto.java`、`link/SharedMemory.java`、`link/RingBuffer.java`、`NativeLink.java` | 纯 Java FFM 开映射、读写区域、SPSC 环 |
| 角色状态机 | `client/ControlStateMachine.java` | 读 `sbFlags` → EVE/STEVE；切 `CameraType`；维护 grab |
| | `client/InputAuthority.java`、`client/WindowFocusBridge.java` | "MC 是否拥有输入" / 伪造 `isWindowActive`（**不碰 OS 窗口**） |
| 每帧同步 | `client/ClientBridge.java` | 发布 `McState`；消费 `teleportSeq` 单次传送；reconcile 代理实体 |
| 输入回放 | `client/InputBridge.java` | 排空 `InputRing` → `KeyMapping` / `MouseHandler` 原生层 |
| 帧导出 | `render/FrameExporter.java` | PBO 异步读回主渲染目标 + 采样位姿 |
| 渲染注入 | `world/mixin/WorldLayerMixin.java` | **构建"MC 层"**：擦世界 + 画 Steve（§5.3） |
| | 其余 11 个 mixin | 键盘/鼠标接管、窗口焦点、地形射线、屏幕背景、爆炸捕获… |
| 代理实体 | `world/ActorProxy.java`、`EntityRegistry.java`、`DamageBridge.java`、`ExplosionDamageSink.java` | 隐形可打的 MC 实体 ↔ 剑星敌人 |
| 虚拟地形 | `world/VirtualTerrain.java`、`TerrainRing.java`、`CollisionField.java` | 剑星地面 → MC 可站可放置的顶面 |
| 启动器 | `SBAutoLauncher.java` | 从 MC 拉起剑星 + `overlay.exe` |

### 3.2 剑星侧

| 模块 | 文件 | 参与构建？ | 职责 |
|---|---|---|---|
| SHM 桥 | `sb-mod/src/shm_bridge.cpp`（559 行）+ Lua 5.4 内核 | ✅ `编译shm.bat` → `stellarcraft_shm.dll` | 建映射、写玩家状态、actor 表、地面列、事件环消费（`sc_read_events_lua`） |
| 游戏逻辑 | `sb-mod/lua/main.lua`（699 行） | ✅ 部署到 `Scripts/` | **F4 热键、玩家位姿镜像、actor 镜像、伤害施加、Eve 可见性、地面采样** |
| 合成器 | `sb-mod/src/overlay.cpp`（1241 行） | ✅ `build_overlay9.bat` + `build_overlay6.bat` → `overlay.exe` | 输入钩子、权威位发布、帧缩放与叠图、可见性门 |
| 协议 | `protocol/stellarcraft_protocol.h`（465 行） | 单一数据源 | 两侧共享的偏移/枚举/flags；`shm_bridge.cpp` **直接 include 它** |
| **骨架（未参与构建）** | `actor_mirror.cpp`、`damage_applier.cpp`、`input_bridge.cpp`、`control_switch.cpp`、`collision_exporter.cpp`、`appearance.cpp`、`stellar_blade_mod.cpp` | ❌ | 这些职责**已改由 Lua + UE 反射实现**（比 C++ 侧写偏移更稳）。保留为路线图 |
| **已放弃的备选** | `dx12_hook.cpp`（797 行）、`version_proxy.cpp` | ❌ | 进程内 DX12 Present hook 路线，见 §3.3 |

### 3.3 为什么放弃"进程内 DX12 hook"这条路（重要决策记录）

`dx12_hook.cpp` 的注释里写的是完整方案：`version.dll` 代理在进程启动早期装 hook →
`hook_thread` 建临时 D3D12 设备解析 `Present` vtable → 把 vtable 换成 `hkPresent` →
每帧上传 MC 帧为 D3D12 纹理，在 `Present` 前画一个全屏四边形。

**放弃理由**（三条，按权重）：
1. **需要 `version.dll` 代理劫持进程启动** → 与 Steam 完整性检查、后续官方更新冲突面大；
   而进程外叠窗**完全不碰剑星进程**，风险为零。
2. **DX12 的合成成本远高于 D3D11**：描述符堆、资源屏障、命令列表管理都要自己维护，
   而只狼是 D3D11（`UpdateSubresource` + 简单状态即可）。
3. 收益只是"能拿到深度/更低的合成延迟"，而**深度合成本身还没验证可行性**（§6-D）。

保留这些文件的目的是：将来若确定能拿到剑星深度，这条路仍是最短路径。

---

## 4. 关键步骤

### 4.1 启动顺序（有严格依赖）

```
1. MC 启动 → StellarCraftMod.initRings() → NativeLink.open() 打开/等待映射
2. SBAutoLauncher 拉起 SB-Win64-Shipping.exe 与 overlay.exe（也可由用户手动先起剑星）
3. 剑星进程内 UE4SS 加载 stellarcraft_shm.dll → Lua main.lua
4. Lua 调 sc_create_mapping()：
     - CreateFileMappingW 建段
     - **memset 清 SbState(0x100) 与 McState(0x100)**   ← 只清状态块，绝不清 96 MiB 大环
5. Lua init() 显式 set_control(0)，打印 "start-up state = EVE"
6. overlay 起窗口、装 WH_KEYBOARD_LL / MOUSE_LL、等 warm-up 基线
```

**"启动必须默认 EVE"需要三层防护，缺一不可**（`CreateFileMappingW` 对已存在的映射是
"打开"而不是"重建"，上局留下的 `flags=0x10` 会活着）：

1. `sc_create_mapping()` 里 memset 状态块；
2. `overlay.cpp` 的 `is_steve_latched()` 有 warm-up 基线：`IN_GAME(0x01)` 置位前一律返回 false
   （防 overlay 比 Lua 先启动读到脏位）；
3. `main.lua` 的 `init()` 显式 `set_control(0)`。

### 4.2 F4 切换（一次按键要穿过四个模块）

```
用户按 F4（焦点在剑星窗口）
  ├─ overlay 的 WH_KEYBOARD_LL 钩子收到 vk=0x73
  │    └─ **必须 CallNextHookEx 放行**（吞掉它，剑星的 UE4SS 就收不到 F4 →「换不回 EVE」）
  │    └─ overlay 只把它当作"若 0x20/0x40 需要更新则更新"的信号
  └─ 剑星 UE4SS 的 RegisterKeyBind(Key.F4) 收到
       ├─ steveMode = not steveMode
       ├─ sc_set_control_lua(steveMode ? 1 : 0)   ← 只碰 0x10
       ├─ apply_eve_visibility(steveMode)          ← pawn.Mesh:SetVisibility + SetHiddenInGame
       └─ 打印 "[StellarCraft] F4 pressed; control=STEVE"
                    ↓
       MC 侧 ControlStateMachine.tick() 下一帧读到 0x10
         ├─ 切 CameraType.FIRST_PERSON
         ├─ 打印 "Character ownership -> STEVE (MC mode)"
         └─ 开始允许接受转发的输入
                    ↓
       overlay 下一帧 should_forward() 变真 → 开始吞剑星输入并转发
```

**关键**：F4 是**电平锁存**（不是边沿），所以"MC 还在主菜单时按 F4"也成立——
这正是 A1 的实现方式。

### 4.3 每帧（overlay 16 ms 定时器）

```
WM_TIMER:
  ensure_shm()                       # 惰性打开映射
  positioned = reposition()          # ★ 无条件调用，内部 500 ms 节流；识别 stale 句柄
  input_tick()                       # 推光标（仅 GUI 打开时）、驱动 ShowCursor
  publish_authority(should_forward(), sb_native_ui())   # 只写 0x20/0x40
  wantVisible = positioned && is_steve_mode() && game_foreground() && !sb_minimized()
  if wantVisible:
      update_frame()                 # 读 slot 像素 → 等比缩放 → UpdateLayeredWindow
      if !g_visible:
          SetWindowPos(HWND_TOPMOST)   # ★ 只在显示这一刻置顶
          ShowWindow(SW_SHOWNOACTIVATE)
  else:
      if g_visible: SetWindowPos(HWND_NOTOPMOST); ShowWindow(SW_HIDE)   # ★ 先降级再隐藏
      每 2 s 打一行 HIDDEN gate 诊断（拒绝原因）
```

缩放规则（`UpdateLayeredWindow` **不做拉伸**，必须自己预缩放）：
等比缩放、按高度贴合、水平居中、底部对齐；`scale src=3840x2054 win=992x517 -> dst=967x517 at (12,0)`。

### 4.4 MC 侧每帧渲染顺序（**字节码偏移级，改渲染前必读**）

```
GameRenderer.renderLevel(float,long,PoseStack)          ← 外层
  offset 585  LevelRenderer.renderLevel(PoseStack,F,J,Z,Camera,GameRenderer,LightTexture,Matrix4f)
                └─ RETURN ← 【WorldLayerMixin 注入点】
                     ① glClear(GL_COLOR_BUFFER_BIT)  擦掉世界（含 Steve 之前的一切）
                     ② 紧接着在同一个注入里画 Steve     ← 顺序硬约束，见 §5.3
  offset 625  renderItemInHand(...)                      ← 原版画第一人称的手（状态天然正确）

GameRenderer.render(float,long,boolean)                 ← 最外层
  offset 358  setOrtho(0, guiW, guiH, 0, 1000, 21000) + ORTHOGRAPHIC_Z   ← GUI 正交投影
  offset 371-396  modelViewStack push / setIdentity / translate(0,0,-11000) / applyModelViewMatrix
  offset 610  Gui.render(guiGraphics, partialTick)        ← HUD 入队（**不是立刻落屏**）
  offset 613  RenderSystem.clear(GL_DEPTH_BUFFER_BIT)
  offset 943  guiGraphics.flush()                         ← ★ HUD 真正落进帧缓冲是在这一步
  return      ← 【FrameExporter 注入点】采样位姿 + 发起异步读回
```

**两条由此推出、不可违反的推论**：
- **擦除必须在 585 之后、625 之前** → 否则要么世界还在，要么手被擦掉（§6-3）。
- **位姿必须在渲染 RETURN 采样**：`publish` 发生在**后续帧**的 poll 里（等 fence），
  那时相机早就变了。

### 4.5 退出与安全态

- **overlay 侧**：每 30 tick（≈0.5 s）轮询 `find_sb_pid()` 与 `is_mc_process_alive()`；
  前 60 次 poll（≈30 s）是启动宽限期；之后连续 6 次缺失（≈3 s）→ 自行退出。
- **MC 侧**：映射消失/心跳停 → 回 EVE，`releaseAll()` 松开所有虚拟按键与鼠标键
  （用 `setDown(false)` 清真实映射，而不是只释放默认键，否则改键后会卡住）。
- **剑星侧**：Lua 检测到 MC 不在 → `set_control(0)`、恢复 Eve 可见。

---

## 5. 关键技术难点与应对

### 5.1 难点总表

| # | 难点 | 现象 | 应对 | 状态 |
|---|---|---|---|---|
| 1 | 吞掉宿主输入 → 宿主菜单点不了 | 剑星主界面按钮全失效 | **拆成角色归属/输入归属两个位**；`NATIVE_UI` 时 MC 放手并松开按键 | ✅ |
| 2 | 加 `IN_WORLD` 门补救 → F4 只在存档里有效 | 必须先进世界才能切 | **从权威路径里删掉所有 IN_WORLD 判定**；只挡显示不挡归属 | ✅ |
| 3 | 擦世界会连手一起擦 | 第一人称没手 | **擦除点前移到"世界画完、手还没画"**（§4.4），让原版画手。**手工补画 `renderItemInHand` 会污染 GUI 投影矩阵 → 整帧精确全零** | ✅ |
| 4 | 进程外叠窗必须自己管 z-order | 切到别的窗口被弹回 MC、啥也点不了 | 建窗**不带** `WS_EX_TOPMOST`；**只在 `g_visible` 时碰 z-order**；显示时置顶、隐藏前降级 | ✅ |
| 5 | 缓存的宿主窗口句柄会失效 | F4 完全没反应，日志连一条 SHOW 都没有 | **前台判定改用进程 ID**（`sb_pid()` 1 Hz 缓存）；`reposition()` **每帧无条件调用**并识别 `!IsWindow` 的 stale 句柄 | ✅ |
| 6 | 鼠标按键编号体系混用 | 末影珍珠扔不出、TNT 点不着 | `kInMouseButton.code` **必须用 GLFW 编号** `0/1/2/3/4`，**不是** `WM_LBUTTONDOWN=1` | ✅ |
| 7 | mixin 包的类必须全部注册 | `IllegalClassLoadError`，游戏直接起不来 | mixin 包里**每个类都要在 `mixins.json` 里**；辅助类放包外 | ✅ |
| 8 | 代理实体缺默认属性表 | `LivingEntity.<init>` → NPE 崩溃 | `FabricDefaultAttributeRegistry.register(...)`；并给 spawn 加 try/catch 兜底 | ✅ |
| 9 | 帧位姿采样时机 | 位姿与像素不对应 | 位姿存到 PBO slot 上，**随那一帧一起发布** | ✅ |
| 10 | 读帧：`glReadPixels` 得到空帧 | 导出全黑 | 用 `bindRead()` + PBO + `glGetTexImage`（读纹理），**照抄原版 `Screenshot.takeScreenshot`** | ✅ |
| 11 | 协议偏移硬编码漂移 | 加深度区后映射大小静默不够 | `shm_bridge.cpp` **直接 include 协议头** + `static_assert`；`tools/check_proto_parity.py` 逐常量比对 | ✅ |
| 12 | 伤害只能 best-effort | 敌人掉血但不出原生硬直/飙血/仇恨 | 见 §6-C：照只狼做**离线反汇编确认 ABI 后调用原生伤害入口** | ⏳ |
| 13 | 进程外叠窗拿不到宿主深度 | 方块不被墙遮挡 | 见 §6-D：先做技术验证（顺 FSR 那条线），拿不到就**划止损线不做** | ⏳ |
| 14 | 前进/后退方向与坐标轴 | 剑星 Y 轴朝上、MC 朝下 | 统一换算常量 `UNITS_PER_BLOCK = 100.0`，映射 `(X, Z, -Y)` | ✅ |

### 5.2 难点 1/2 —— 这是整套方案的分水岭

详细论述见 §2.1。一句话：**「谁驱动角色」和「谁收输入」必须是两个独立概念**，
并且**宿主机是权威**。任何把它们合并的做法都会在上述两个方向之间来回摇摆。

### 5.3 难点 3 —— 🚨 本项目最贵的一次教训（务必读完再动渲染代码）

曾经的实现（**错误**）：

```java
// ClearWorldMixin，挂在 Gui.render 的 HEAD
GlStateManager._clearColor(0,0,0,0);
GlStateManager._clear(GL_COLOR_BUFFER_BIT, false);      // 擦世界
accessor.stellarcraft$renderItemInHand(new PoseStack(), camera, partialTick);   // ← 手工补画手
```

后果：**MC 窗口纯黑 + 剑星上没有 UI + 导出帧精确全零**（3155 万个字节里非零 0 个）。原因两条：

1. `renderItemInHand` 开头就是
   `resetProjectionMatrix(getProjectionMatrix(getFov(camera, f, false)))` —— **换掉全局投影矩阵且从不恢复**。
   此时 `GameRenderer.render` 早在偏移 358 装好了 GUI 正交投影 → **之后所有 HUD 都用 3D 透视投影画** → 落不到画面上。
2. 它依赖 `RenderSystem.getModelViewMatrix()` 仍是**相机变换**；GUI 阶段那是 `translate(0,0,-11000)`
   → 补画的手被推到远裁剪面（`far=768`）之外，**直接裁掉**。

正确做法：见 §4.4。**擦除点前移 + 与 Steve 的绘制合并进同一个注入。**

**为什么必须合并进同一个注入**：`@At("RETURN")` 上挂两个 mixin 时，
**两个 callback 的相对顺序是未定义的**。若 Steve 先画、擦除后跑，Steve 就被擦掉了
（正是"人物模型隐形"那个 bug）。合并后顺序显式：**先擦，再画 Steve**。

**判据（值得记住）**：**帧"精确全零"是变换错，不是状态/判定错**；"大部分黑"才是状态问题。
这条判据把方向从"门控/条件"一次扭到了"矩阵"。

### 5.4 难点 4/5 —— 顶层窗口的两条铁律

```
铁律一：z-order 只在可见期间存在
  CreateWindowExW 不带 WS_EX_TOPMOST
  显示：SetWindowPos(HWND_TOPMOST) → ShowWindow(SW_SHOWNOACTIVATE)
  隐藏：SetWindowPos(HWND_NOTOPMOST) → ShowWindow(SW_HIDE)
  且 reposition() 里的 z-order 变更必须包在 if (g_visible) 里
  （否则"隐藏状态下重新定位"会悄悄把它又变回 topmost → 「切窗口被弹回 MC」）

铁律二：跨进程判定用进程 ID，不要用缓存的窗口句柄
  UE4 先建 splash 窗口、再建真正的渲染窗口 → 早期抓到的句柄会失效但仍非 NULL
  失效句柄的 GetWindowThreadProcessId 返回 0 → 前台判定恒为假 → 门永久关闭
  → game_foreground() 用 fgPid == sb_pid() 比较
  → reposition() 必须能识别 !IsWindow(g_sbWnd) 并重新解析，且必须每帧被调用
```

**诊断纪律（这条是方法论，不是技巧）**：门控/条件类逻辑**必须有一条节流的"拒绝原因"日志**。
只在状态翻转时打 SHOW/HIDE 的写法，会让"门一直不开"变成零线索——本项目在这上面卡了整整两轮。
现在每个 tick 的拒绝原因都会以约 2 s 节流打出全部条件项：

```
[overlay] HIDDEN gate steve=0 foreground=0 notMinimized=1 positioned=1
  | sbWnd=... sbValid=1 sbPidWnd=38952 sbPidProc=38952
  | fgWnd=... fgPid=14796 overlayWnd=... sameProc=0
  | sbFlags=0x1 mcFlags=0x5 base=...
```

（注意 `notMinimized=1` **不能**证明句柄有效——`sb_minimized()` 对"失效句柄"也返回 false，
所以另行打印了 `sbValid`。）

---

## 6. 已知未完成项与后续路线

### A. 深度合成（遮挡）—— **不做技术验证就不要开工**

现状：世界层对宿主深度缓冲做深度测试**完全没做**，所以放在剑星场景里的方块不会被墙挡住。
`DESIGN.md` 里这条一直挂着。

拦路虎：我们不 hook DX12，拿不到剑星深度。**但有一条线索**：剑星自带
`amd_fidelityfx_dx12.dll`（FSR），而 **FSR 本来就需要深度输入** → 剑星确实把深度交给了这个 DLL。
顺着它找比从零 hook DX12 现实得多。

**止损线**：拿不到宿主深度就**不做**世界层深度合成。（换 DXGI/DirectComposition 只提升画质，
拿不到遮挡，不要误当成解法。）

### B. 重投影掩盖延迟

协议 v2 已经把原料备好（帧位姿 + near/far/vFov + `frameSeq`），**目前没有任何消费者**。
下一步只需一个消费端：把 slot 位姿重投到"当前"剑星相机位姿。参数（如 `pose_lag`）应按 GTA5
示例那样**用"金墙 vs 天空线"逐帧实测**，不要照抄别人的数值。

### C. 原生伤害（把 best-effort 变成真受击）

现状：`main.lua` 的 `damage_actor()` 分层试探 `ReceiveAnyDamage → TakeDamage → 直接改 Health`。
**敌人会掉血，但不出原生硬直/飙血/仇恨。**

仿照只狼的正确形态（`reference/SekiroCraft-Passthrough/src/native_hit.hpp`）：

> "This backend constructs an **owned normal-sword packet**. It never keeps a live trace packet
> or attacker pointer. … **Call only from the fingerprinted AttackManager update, after actor lookup.**"

即：**构造一个 owned 攻击包交给原生 AttackManager**，而不是改血。
配套 `src/combat_game_stub.S` 是手写 x64 汇编钩子桩（`.seh_proc` + 保存全部 GPR/XMM/EFLAGS）——
这就是"离线反汇编确认 ABI"的落地形态。

**这是我们与只狼共有的缺口**（只狼 README 也写"完整原生受击、击退、弹反、武器参数适配仍在开发"）。
剑星这边的入口需要 UE4SS Live Property Editor + IDA 定位（`docs/SDK_MAPPING.md` §2.4 已列为待逆向）。

### D. 离线自测（成本最低、回报最高的一条）

`tools/` 里已经有四件不用开游戏就能跑的东西，**改任何一层之前先跑它们**：

| 工具 | 验证什么 |
|---|---|
| `tools/check_proto_parity.py` | 编译并运行 C++ 侧全部 `offsetof`/`sizeof` 断言，再求值 `Proto.java` 的常量表达式，**逐项比对** |
| `tools/test_pose_pipeline.py` | 自造一个 v2 段写入已知位姿，再跑 `probe_shm.py` 断言读回值匹配 |
| `tools/test_damage_conversion.py` | 用自编的 `lua.exe` 抽出 `main.lua` 里**真实的档位判定代码**跑用例（不是抄副本） |
| `tools/probe_shm.py` / `probe_pixels.py` | 直读运行时共享内存：头部/状态/位姿/slot 像素 |

### E. 输入覆盖度

目前转发的键：WASD/跳跃/潜行/疾跑/攻击/使用/拾取 + 鼠标移动/按键/滚轮。
**未覆盖**：F1/F3/F5 等 MC 内部功能键（F5 目前走剑星侧 UE4SS 的按键绑定转成相机切换）、
文本输入（`kInText` 已定义但未接）、手柄。

---

## 7. 可运行的实现方案（分阶段）

### 阶段 M0：环境与骨架（已完成）

产物与部署位置：

| 产物 | 构建 | 部署路径 |
|---|---|---|
| `stellarcraft-0.1.0.jar` | `JAVA_HOME="C:/Users/StickySlime/jdk-21.0.12.1+1" "<gradle-8.8>/bin/gradle.bat" build --no-daemon` | `D:\Release 2.7.4\.minecraft\versions\1.20.1-Fabric 0.19.5\mods\` |
| `stellarcraft_shm.dll` | `sb-mod/编译shm.bat` | `…\SB\Binaries\Win64\ue4ss\Mods\StellarCraft\` |
| `overlay.exe` | `sb-mod/build_overlay9.bat`（编译）→ `build_overlay6.bat`（链接） | `…\SB\Binaries\Win64\` |
| `main.lua` | 自编 `luac.exe -p lua/main.lua` 验语法 | `…\ue4ss\Mods\StellarCraft\Scripts\` |

> **部署纪律**：替换前先关两个游戏进程（`tasklist` 检查 → **请用户关闭**，**不要 `taskkill /F`**，
> 会触发 PCL 假崩溃报告）→ 确认消失再替换 → md5 校验源↔部署一致。
> 否则会出现"剑星运行中 DLL 被占用、替换失败、跑的还是旧版"这种白测一轮。

### 阶段 M1：角色/输入归属 + 相机切换（已完成）

落点：`ControlStateMachine`（切 `CameraType`）、`InputBridge`（回放）、`main.lua`
（F4 绑定 + `apply_eve_visibility`）、`overlay.cpp`（`publish_authority`）。

### 阶段 M2：把"MC 层"画到剑星上（已完成，本轮刚修好）

落点：`FrameExporter`（PBO 异步读回）、`WorldLayerMixin`（擦世界 + 画 Steve）、
`overlay.cpp`（缩放 + `UpdateLayeredWindow` + 可见性门）。

### 阶段 M3：玩法打通（进行中）

- 地形/放置：`VirtualTerrain` + Lua 地面采样（9×9 / 0.5 m / 半径 4 m / ≈10 Hz）
- 伤害：`ActorProxy` + `DamageBridge` + `ExplosionDamageSink` + Lua actor 镜像与档位换算
- 待办：§6-C 原生伤害入口

### 阶段 M4：画质与深度（未开始，需先做技术验证）

见 §6-A / §6-B。

### 编译速查（完整见 `.workbuddy/memory/BUILD_NOTES.md`）

```bash
# MC（注意 编译MC.bat 指向 jdk-17，是错的）
JAVA_HOME="C:/Users/StickySlime/jdk-21.0.12.1+1" \
  "C:/Users/StickySlime/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat" \
  build --no-daemon

# 剑星 DLL / overlay
cd stellarcraft/sb-mod && cmd //c 编译shm.bat
cmd //c build_overlay9.bat && cmd //c build_overlay6.bat   # build_overlay9 的链接步骤会报 uuid.lib，忽略

# Lua 语法
./build/manual/luac.exe -p lua/main.lua
```

---

## 8. 验证方法

### 8.1 离线（不开游戏）

见 §6-D 的四件工具。**改协议必跑 `check_proto_parity.py`；改 Lua 必跑 `test_damage_conversion.py`。**

### 8.2 实机验收清单

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 先启动剑星，再启动 MC（MC 会自动拉起 `overlay.exe`） | MC 日志 `Linked to Stellar Blade! (sbPid=…, version=2)` |
| 2 | 在剑星主菜单按 F4 | UE4SS 日志 `F4 pressed; control=STEVE`；MC 日志 `Character ownership -> STEVE` |
| 3 | 剑星画面 | 出现 MC 准星 → 工具栏（`overlay.log` 有 `SHOW (steve mode + foreground)`） |
| 4 | WASD / 空格 / 疾跑 / 潜行 / 鼠标视角 | 由 MC 处理，剑星的角色不动 |
| 5 | F5 切第三人称 | 剑星画面里出现 Steve 身体 |
| 6 | 打开 MC 背包（E） | 背包可开可关，鼠标光标出现，四周能看到剑星画面 |
| 7 | 右键放方块 / 扔珍珠 / 打火石点 TNT | 剑星场景里出现方块；TNT 炸 Naytiba 时剑星控制台有 `[StellarCraft] event type=6 …` |
| 8 | **切到浏览器/别的窗口** | **不被弹回、不被 MC 画面盖住** |
| 9 | 再按 F4 | 回 EVE，overlay 立即消失，剑星画面纯净 |
| 10 | 进剑星自己的菜单 | 按钮可点（`NATIVE_UI` 生效） |
| 11 | 关掉任一侧进程 | overlay 3 s 内自行退出，另一侧回安全态 |

### 8.3 日志判据（三份日志联查）

| 日志 | 位置 | 看什么 |
|---|---|---|
| `overlay.log` | `…\ue4ss\Mods\StellarCraft\` | `SHOW/HIDE`、`HIDDEN gate …`（拒绝原因）、`alive steve=1 frames=N`、`scale src=… -> dst=…`、`SB window … went away; re-resolving` |
| `UE4SS.log` | `…\Win64\ue4ss\` | `F4 pressed; control=…`、`Eve mesh hidden` |
| `latest.log` | MC `versions\…\logs\` | `Linked to Stellar Blade!`、`Character ownership -> …`、`[StellarCraft-FX] frame N …` |

**`[StellarCraft-FX]` 行的读法**（帧内容判据）：

```
[StellarCraft-FX] frame 600 steve=0 lvl=1 scr=null hideGui=0 rt=854x480 rtFbo=1 fbo=1 tex=1 unit=33984 pub=0
  | 854x480 opaque=408304/409920 first=(9,6,7) xcheck=408304/409920 top=...
```

| 观察 | 含义 |
|---|---|
| `steve=0` 且 `opaque≈40.8 万` | EVE 模式：世界正常渲染（对照组） |
| `steve=1` 且 `opaque≈1.5 万` | STEVE 模式：世界已擦，只剩 HUD —— **这是正确状态** |
| `steve=1` 且 `opaque≈39 万` | 世界没被擦掉（`WorldLayerMixin` 没生效 / `mc.level == null`） |
| `opaque = 0`（**精确全零**） | **变换错**：某个阶段破坏了投影/model-view |
| `xcheck ≠ opaque` | 两个读回路径不一致 → 下载路径有问题 |

---

## 9. 一句话总结

> **架构骨架照抄只狼**（权威拆分 / 分层导出 / 代理实体 / 地形采样 / 共享内存 + 事件环），
> **四条落地路径因为宿主是 DX12/UE4 而全部重写**（UE4SS+反射代替反汇编注入、
> 进程外叠窗代替游戏内 D3D11 hook、三进程代替两进程、纯 Java FFM 代替 JNI）。
> 与 GTA5 方案**架构相反**（它宿主持有相机与角色，MC 只是跟随），
> 只有"离线自测 / 重投影 / 槽描述符 flags"三块值得借鉴。
>
> 最大的两个结构性风险都在**边界**上：**输入归属**（宿主菜单必须仍然可用）
> 和**顶层窗口生命周期**（z-order + stale 句柄）。
> 这两处各自对应本项目最贵的两次排查，规律已经写进 §5.3 / §5.4。
