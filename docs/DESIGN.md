# StellarCraft — 技术设计文档

> 用 Minecraft 玩家在《剑星》(Stellar Blade) 的世界里游玩:MC 的物理、背包、HUD 和方块玩法,作用于剑星的世界、NPC 和战斗。
>
> 状态:v0.1 · 架构参照 SkyCraft (github.com/chasmlol/SkyCraft)

---

## 0. 结论先行(能不能做到?)

**能,而且剑星侧比预期乐观得多。** 关键判断依据:

| 维度 | 上古卷轴5 (SkyCraft 参考) | 剑星 (Stellar Blade) |
|---|---|---|
| 引擎 | Creation Engine(闭源) | **UE4.26**(`SB//UE4/Release-4-26` 已确认) |
| 注入通道 | SKSE(官方认可) | **UE4SS**(社区成熟,剑星专用 fork) |
| 拿符号/偏移 | Address Library | **UE4SS 的 UHT/C++ Header Dumper** |
| 运行时读对象 | CommonLibSSE | **UE4SS C++ Modding API**(基于 UE 对象系统) |
| 注入方式 | SKSE loader | **dwmapi.dll 代理注入** |

UE4SS 之于剑星,就像 SKSE 之于天际——社区已经趟平了注入、读 GWorld/AActor、拿偏移、hook 渲染这条最难的路。**本项目不是"从零逆向剑星",而是站在 UE4SS 的肩膀上写一个 C++ mod。**

---

## 1. 核心原则

**两个游戏都不重写。** Minecraft 跑自己未改动的游戏逻辑(移动、碰撞、战斗、背包、方块);剑星跑自己的世界(地形、建筑、NPC、AI、任务、存档)。两个 mod 只做**翻译**:

- 剑星告诉 MC:世界长什么样、NPC 在哪。
- MC 告诉剑星:玩家在哪、打了谁、要往画面里叠什么。

任何"用 C++ 重写 MC 机制"或"用 Java 重写剑星机制"的冲动,都说明设计走偏了。

---

## 2. 目标环境

| 项 | 值 |
|---|---|
| 剑星 | PC 版,UE4.26,`SBBinaries/Win64` 或 `SB/Binaries/Win64` |
| 注入框架 | UE4SS(剑星 fork:Chrisr0/RE-UE4SS、RevOGUwU 更新版) |
| Minecraft | 26.3 + Fabric(fabric-api 0.161+),Mojang 映射 |
| Java | 25 |
| 编译 | VS 2022+、CMake、xmake(UE4SS 用 xmake) |

> ⚠️ 合规提示:注入剑星违反 Shift Up / Sony 的服务条款,有封号风险;本仓库仅供技术研究,不含任何破解/盗版内容,使用需自担风险。

---

## 3. 组件总览

```
┌──────────── StellarBlade.exe (UE4.26) ────────────┐   ┌──────── javaw.exe (Minecraft 26.3) ────────┐
│  stellarcraft_sb.dll (UE4SS C++ mod)              │   │  stellarcraft (Fabric mod)                 │
│                                                   │   │                                           │
│  CollisionExporter ── 剑星碰撞 → AABB ────────────┼──▶│  CollisionField → 注入 MC 碰撞查询          │
│  ActorMirror       ── 附近 NPC(位置/体型/血) ─────┼──▶│  ActorProxy 实体(隐形、可攻击)              │
│  InputBridge       ── 原始键盘/鼠标 ──────────────┼──▶│  输入处理器(当作 MC 窗口有焦点)            │
│  HitApplier        ── "NPC 打了玩家 X" ───────────┼──▶│  player.hurt(stellarcraft:sb_* 伤害源)      │
│                                                   │   │                                           │
│  PlayerPuppet      ◀─ 玩家位置/朝向/姿态 ──────────┼────│  真实 MC LocalPlayer 物理                   │
│  CameraDriver      ◀─ view+projection 矩阵 ───────┼────│  GameRenderer 相机                         │
│  DamageApplier     ◀─ "你打了 NPC X" ─────────────┼────│  ActorProxy.hurt() hook                    │
│  OverlayCompositor ◀─ 颜色+深度纹理(GPU) ─────────┼────│  离屏渲染:世界层/手部层/GUI 层              │
└───────────────────────────────────────────────────┘   └───────────────────────────────────────────┘
            共享内存 (Local\StellarCraft_v1) + 命名事件 + 共享 GPU 纹理
```

---

## 4. 坐标映射

Unreal 是 Z-up、单位厘米;Minecraft 是 Y-up、单位方块(1 块 = 1 米)。**1 方块 = 100 UU。**

```
mc.x =  sb.x / 100
mc.y =  sb.z / 100
mc.z = -sb.y / 100      (Unreal +Y 朝前/北;MC -Z 是北)
mc.yaw   = f(sb.rotYaw)   (符号/偏移在 Phase 0 用单测钉死)
mc.pitch = f(sb.rotPitch)
```

- 剑星地形垂直跨度可能超过 MC 默认 384 格 → 镜像世界用自定义 dimension_type 扩高。
- 剑星的关卡/地图 → 每个 map 映射到独立 MC dimension;切换地图即传送到对应维度。

---

## 5. 镜像世界(Minecraft 侧)

MC 跑一个普通单机世界("镜像世界"):

- **虚空生成器**:除了玩家放的方块,没有任何 MC 方块。
- `doMobSpawning false`、`doDaylightCycle false`;时间和天气由剑星驱动。
- 集成服务器正常跑;碰撞注入是公共代码,服务端移动校验看到同一世界,不会回弹玩家。

### 5.1 CollisionField:剑星地形如何进入 MC 物理

MC 的移动代码(`Entity.collide` 等)会向 level 查询碰撞形状(`VoxelShape`,即 AABB 的并集)。一个 Mixin 把 **CollisionField 里额外的形状追加到该查询**。MC 自己的碰撞解析、上台阶、重力、冲刺跳、边缘潜行全部**原封不动**地对这些形状生效。

- 字段是稀疏 AABB 存储,按 16³ section 分桶,**1/8 方块分辨率**(12.5 UU)。
- 这些形状**不是方块**,不占方块网格,所以玩家方块能自由放在剑星几何旁/上。
- **斜坡**变成 1/8 块微台阶,每个都远小于 MC 的 0.6 上台阶高度,爬坡自然顺滑。
- **陡坡**:表面法线超过阈值(≈50°,对应剑星可攀爬坡度)的格子抬成整墙柱,MC 的上台阶规则自然拒绝。

**数据来源(剑星侧 CollisionExporter):**

| 阶段 | 方法 | 覆盖 | 代价 |
|---|---|---|---|
| A(MVP) | 在玩家周围网格做 Unreal 射线/胶囊 trace,近处加密、跨帧摊销,每列多次命中处理悬挑 | 地形、多数静态网格 | 便宜;漏薄几何 |
| C(最终) | 遍历已加载 UWorld,读实际碰撞图元(box/capsule/convex/landscape heightfield),在工作线程体素化 | 一切,含 mod 内容和开启的门 | 复杂(解码 UE 物理形状)但精确 |

数据按 section 以增量流式发送;MC 保留玩家周围一圈,逐出远处 section。

---

## 6. 玩家

**Minecraft 对玩家位置和物理是权威的。**

1. MC 每渲染帧发 `PlayerState`:插值后的位置/朝向、姿态、含视角摆动的完整 view 矩阵 + projection/FOV。
2. 剑星插件(**PlayerPuppet**):禁用玩家自身移动,每帧把 PlayerCharacter 和它的碰撞胶囊移动到该位置。
   - 剑星玩家引用作为**木偶**留在世界里,保证 NPC 的 AI 索敌/潜行、触发体积、任务位置检查、投射物命中玩家都正常工作。
3. **CameraDriver** 强制剑星第一人称,用 MC 的 view/projection 覆盖相机;隐藏剑星玩家身体和手臂。

---

## 7. 输入

- 剑星窗口持有系统焦点。**InputBridge** 通过剑星输入设备管理器读原始输入,**从剑星控件吞掉**,经 Mixin 入口转发给 MC 的 KeyboardHandler/MouseHandler。
- MC 窗口隐藏但被告知"有焦点",所以不会暂停或释放鼠标。
- **路由模式**:
  - **游玩**:输入给 MC;剑星只收白名单(暂停/系统菜单、Activate 键)。
  - **MC 界面打开**(背包/合成/箱子):输入给 MC,覆盖层显示 MC 光标。
  - **剑星菜单打开**(对话/商店/地图/读盘):输入给剑星,MC 客户端冻结。
- **"使用"仲裁**:按 MC 使用键时两边各自拾取目标,最近者胜出;剑星目标触发剑星 Activate(开门、交互、对话)。

---

## 8. 战斗

### 8.1 剑星 NPC 进入 Minecraft:ActorProxy

剑星每个 ~64 格内的角色,MC 服务器生成一个 `actor_proxy` 实体:

- **隐形**(剑星画真实角色)。
- **hitbox** 来自角色的胶囊尺寸,每 tick 更新;位置/旋转插值。
- 携带角色 uid、镜像血量比例、敌意/死亡/boss 标志。
- 代理是真正的 LivingEntity,所以剑的挥砍、攻击冷却、暴击、横扫、锋利/火焰附加、击退、弓箭、三叉戟、喷溅药水全部用**原版 MC 代码**生效。

### 8.2 你打 NPC

1. 原版 MC 在代理上算最终伤害(`hurt` 路径)。
2. mod 拦截结果,保持代理存活(它不是真 NPC),发 `HitActor {uid, damage, knockback, isCrit, sourceItem, fireTicks}`。
3. 剑星 **DamageApplier** 走游戏自己的受击管线处理:硬直、飙血、音效、仇恨、犯罪/攻击判定(对应剑星的仇恨与敌意系统)。
4. **伤害缩放(可配置)**:MC→剑星 乘以 `5 + 0.25 × 等级`(MC 武器伤害个位数,剑星敌人血量高);剑星→MC 除以 5。

### 8.3 NPC 打你

1. 剑星对玩家木偶的命中在剑星侧 hook 并**取消**。
2. 插件发 `PlayerHurt {amount, type, sourceUid, direction}`。
3. MC 用自定义伤害源 `player.hurt()`;护甲、格挡、无敌帧、击退全部原版 MC。
4. **MC 血量为权威**;剑星玩家血量镜像成比例,让 NPC 行为(逃跑、处决)仍合理。MC 死亡 → 剑星玩家死亡 → 剑星正常死亡/读档流程。

---

## 9. 渲染

剑星画世界。MC **只画自己的东西**,离屏以剑星分辨率渲染,分三层:

| 层 | 内容 | 合成 |
|---|---|---|
| **World** | 放的方块、方块实体、掉落物、箭、粒子;关天空/云/雾 | 帧中,**对剑星深度缓冲做深度测试**,剑星的墙正确遮挡你的方块 |
| **Hand** | 第一人称手臂 + 手持物 + 挥砍/装备/进食动画 | 剑星场景之后、HUD 之前 |
| **GUI** | 快捷栏、红心、饥饿、经验、准星、所有 MC 界面 | 最顶层 |

- **传输**:颜色/深度纹理在 GPU 上共享。MVP 用 PBO 读回上传(约 1 帧延迟);生产路径用 `WGL_NV_DX_interop2`(OpenGL)或 `VK_KHR_external_memory_win32`(Vulkan)+ 共享 fence。
- **帧锁步**:MC 的帧上限和 vsync 禁用。剑星发"第 N 帧开始"+ 相机 → 两游戏并行渲染 → 剑星等 MC"第 N 帧就绪"fence(带超时)→ 超时复用第 N-1 帧。
- **光照**:MC `dayTime` 由剑星 `GameHour` 驱动,天气跟随剑星,保证方块着色大致对齐场景。

---

## 10. 协议 / IPC(已实现)

**共享内存 `Local\StellarCraft_v1`**,字节布局单一数据源:
- `protocol/stellarcraft_protocol.h`(C++,剑星侧)
- `mc-mod/.../link/Proto.java`(Java,MC 侧)

内存分区(offset):

| 区域 | 偏移 | 方向 | 说明 |
|---|---|---|---|
| Header | 0x0 | 双向 | magic/version/双 PID/心跳 |
| SbState | 0x100 | SB→MC | seqlock,关卡/锚点/teleport/游戏时间 |
| McState | 0x200 | MC→SB | seqlock,玩家插值位置/姿态/相机/20Hz tick |
| ActorTable | 0x300 | SB→MC | seqlock,256 个角色镜像 |
| InputRing | 0x1000 | SB→MC | SPSC 环,输入/受伤事件 |
| EventRing | 0x10000 | MC→SB | SPSC 环,命中/死亡/爆炸/方块变更 |
| CollisionRing | 0x20000 | SB→MC | SPSC 环,32MiB,AABB 流 |
| OverlayPixels | … | MC→SB | 三缓冲 RGBA8(3×3840×2160×4) |
| RenderRing | … | MC→SB | 64MiB,方块网格/图集 |

- 热路径全定长小端结构体,无序列化库;seqlock 无锁读写;SPSC 环用绝对游标(Disruptor 风格)。
- 命名事件负责唤醒;心跳检测崩溃;任一侧死掉,另一侧退回安全态。

---

## 11. 其他系统

- **NPC 与放置方块**:MC 方块在 MC 里真实存在,但剑星 NPC 也要撞你的建筑。插件用 `BlockChange` 同步剑星侧碰撞(MVP:隐形碰撞盒;最终:每 chunk 一个合并 Havok 形状)。
- **存档/读档**:剑星存档存 `saveId`;保存时 MC flush 镜像世界并快照;读档时恢复快照,保证方块/背包一致回退。

---

## 12. 分阶段路线图

| # | 阶段 | "完成"标志 |
|---|---|---|
| 0 | **链接** | 双 mod 经共享内存握手;坐标映射单测通过 |
| 1 | **剑星里用 MC 物理行走** | CollisionField A + CameraDriver + InputBridge;真实 MC 移动 |
| 2 | **覆盖层** | 手部 + GUI 层合成;真实 MC 快捷栏/背包可用 |
| 3 | **战斗** | ActorProxy + HitActor + PlayerHurt + 血量镜像 + 死亡 |
| 4 | **方块** | 在剑星表面放/拆方块;世界层深度合成;NPC 撞建筑 |
| 5 | **完整世界** | CollisionField C(建筑/多层);水;激活仲裁;跨地图维度 |
| 6 | **持久化与打磨** | 存档快照;时间/天气同步;剑星掉落→MC 物品桥;第三人称;自动启动 |

---

## 13. 风险

| 风险 | 缓解 |
|---|---|
| UE 物理形状解码(阶段 C)难 | 阶段 A 射线字段足以交付 1–4 |
| 剑星受击管线函数需逆向 | UE4SS 的 live property editor + FName dump;fallback 用属性修改 |
| GL/Vulkan 与 D3D11 的 GPU 互通 | 先做 CPU 读回路径 |
| 帧锁步增加延迟/卡顿 | 超时 + 复用上一帧 |
| Mixin 目标随 MC 版本漂移 | 锁 26.3;Mixin 集中一个包 |
| 两游戏内存/GPU 开销 | MC 几乎不渲染(虚空世界),JVM 堆上限 3GB |
| 剑星更新破坏 UE4SS 签名 | 跟随社区 fork 更新;AOB 重扫 |

---

## 14. 仓库结构

```
stellarcraft/
  protocol/stellarcraft_protocol.h   协议单一数据源(C++)
  mc-mod/                            Minecraft Fabric mod(Java)
    src/main/java/dev/stellarcraft/
      link/        Proto.java, SharedMemory.java, RingBuffer.java
      world/       CollisionField.java, EntityCollisionMixin.java,
                   ActorProxy.java, EntityRegistry.java, DamageBridge.java
      client/      ClientBridge.java
      render/      OverlayCompositor.java
      StellarCraftMod.java, NativeBridge.java
    src/main/cpp/bridge.cpp           JNI 共享内存映射
    src/main/resources/              fabric.mod.json, mixins, gradle
  sb-mod/                            Stellar Blade UE4SS C++ mod
    src/stellar_blade_mod.cpp        入口
    src/collision_exporter.cpp       碰撞导出
    src/actor_mirror.cpp             NPC 镜像
    src/input_bridge.cpp             输入桥
    src/overlay.cpp                  覆盖合成
  docs/DESIGN.md                     本文档
```
