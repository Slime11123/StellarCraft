# universal-modder / minecraft-gta5-passthrough 分析

> 调研日期：2026-10-08　本地副本：`reference/universal-modder`（HEAD `8370faa`，51 MB）  
> 上游：`github.com/rehan-remade/universal-modder` → `examples/minecraft-gta5-passthrough`  
> 一手材料：`README.md`(270 行)、`knowledge/games/gta-v/minecraft-passthrough.md`(229 行，作者自己写的 21 条 gotcha)、  
> `mc/src/client/.../{PassthroughClient,HostLink,HostState,ClientInput,FrameExporter}.java`、  
> `mc/src/client/.../mixin/CameraMixin.java`、`gta/src/{script.cpp,compositor.cpp,compositor.h}`  
> 注：本文只做分析。**不涉及任何代码修改。**

---

## 1. 整体架构

**两进程 passthrough + 双通道**，宿主是 GTA V Legacy（Steam build 3889），客端是 Minecraft 26.3 Fabric。

```
GTA V (story mode)                                   Minecraft 26.3 + Fabric
  MCPassthrough.asi (ScriptHookV script)  ──WebSocket 127.0.0.1:25599──▶  cam / ground / key / slot / cmd
                                          ◀─────────────────────────────  explosion / projhit / mobs
  ReShade add-on (compositor.cpp)  ◀── 共享内存 Local\MCPassthroughFrame ──  world RGBA + depth, overlay RGBA
  MCPassthrough.fx: 深度测试 vs GTA 的 reversed-Z 深度, overlay 置顶
```

四个产物：

| 产物                  | 技术栈                    | 职责                                    |
| ------------------- | ---------------------- | ------------------------------------- |
| `MCPassthrough.asi` | ScriptHookV + MSVC     | 相机/地面采样、输入拦截转发、GTA 侧演出（爆炸、prop、警察、天气） |
| `compositor.cpp`    | ReShade **add-on API** | 把 MC 帧上传成纹理，给 `.fx` 喂 uniform         |
| `MCPassthrough.fx`  | ReShade 效果着色器 (HLSL)   | 深度测试、重投影、重打光、色调匹配、overlay             |
| `mc/` Fabric mod    | Java 25 + Loom         | 相机跟随、地面成障、方块回传、帧导出、事件                 |

**通道分工是关键设计**：

- **控制面** = WebSocket + JSON 文本（`HostLink` 是 MC 侧起的 server，GTA 是 client，实现重连）
- **数据面** = 命名共享内存，**单向上行**（MC → GTA 像素），容量 ≈ 4096 + 3 × (3840×2160×4×3) ≈ **298 MB**
- 下行事件（爆炸/箭矢命中/火工）也走 WebSocket，**不需要像素回传**

**与其它两个参考项目的定位差异**：这个示例的方法论是"**宿主渲染主画，客端把画面和深度塞进宿主的合成管线**"，而不是"客端叠一层 UI"。所以它的重头戏全在**合成**上（`.fx` 是整个示例技术含量最高的部分）。

---

## 2. 关键实现机制

### 2.1 相机权威：**宿主驱动客端**（与我们相反）

GTA 每帧把"它这一帧要用的相机"发过来，MC 侧 `CameraMixin` 在 `Camera.update` → `alignWithEntity` 的 **TAIL** 处直接覆写：

```java
this.xRot = p.pitch();  this.yRot = p.yaw();
this.rotation.rotationYXZ(PI - yaw*DEG, -pitch*DEG, roll*DEG);   // 连 roll 都接管
FORWARDS.rotate(this.rotation, this.forwards);  UP.rotate(...);  LEFT.rotate(...);
this.matrixPropertiesDirty |= 3;
this.setPosition(x, y, z);
```

还接管了 `calculateFov`（HEAD 处 `cir.setReturnValue(p.fov())`）和裁剪面（TAIL 处 `FrameExporter.setFar(depthFar)`）。

两个细节值得注意：

- **相机会撞进 Steve 头里**：GTA 贴墙时把相机拉近，MC 相机就进到玩家头内部 → 用 `eyePosition.distanceToSqr(cam) < 0.8²` 判定，命中就强制 `detached = true` 抬到第三人称。（我们做过等价的"第一人称手部/身体遮挡"处理。）
- **飞行时是反过来的**：`g_drive` 打开后 **MC 自己算物理、宿主跟随**，相机锚定在 Steve 身上 —— 代码里靠 `p.drive()` 分支做位移补偿。

**结论：宿主持有相机是主模式，MC 只在"飞行"这一条支线上反客为主。** 这与我们的"MC 拥有玩家与相机"完全相反。

### 2.2 画面合成（整个方案的核心）

**MC 侧导出三层**（`FrameExporter`）：

1. 在 `GameRenderer.renderLevel` 画手**之前**：`copyTextureToBuffer` 抓 world **colour + depth**
2. 紧接着 `encoder.clearColorTexture(target.getColorTexture(), TRANSPARENT)` —— **把颜色缓冲清空**
3. 于是之后画的手/HUD/界面**自成一层**，帧末再 `copyTextureToBuffer` 抓成 overlay

这个技巧很聪明：**用一次清屏把"世界"和"HUD"天然切成两层**，不需要分别渲染两遍。  
（我们的 `ClearWorldMixin` 也是利用 `Gui.render` 这个时机点擦屏，思路同源，但我们只留下一层。）

**共享内存布局**（header 4096 B + 3 slot）：

| 偏移                | 内容                                                          |
| ----------------- | ----------------------------------------------------------- |
| 0/4/8/12          | magic `MCPT` / version 1 / header bytes 4096 / slot count 3 |
| 16/24/28          | slot stride / max W 3840 / max H 2160                       |
| 32/40/44          | publish counter / latest slot / MC pid                      |
| `256 + 128*i`     | 第 i 个 slot 描述符                                              |
| slot +0           | `seq`（**奇偶做 seqlock**）                                      |
| slot +8/+16       | MC frame / host frame（用于配对）                                 |
| slot +24..40      | w / h / near / far / **vertical fov**                       |
| slot +44          | flags：`1`=zZeroToOne、`2`=rows bottom-up、`4`=reversed Z      |
| slot +48..84      | 相机 x/y/z（double）、yaw/pitch/roll、firstPerson                 |
| slot +88/+96      | capture nanos / publish nanos                               |
| `4096 + stride*i` | 三层像素：world RGBA8（预乘 alpha）、depth **float32**、overlay RGBA8  |

**读回是异步的**：`copyTextureToBuffer` + 回调，配 3 个 GPU buffer 组成 ring；`busy` 标志带 **1 秒超时**兜底（`STUCK_NANOS`）防止 ring 卡死。发布用 `VarHandle.fullFence()` 分隔，seq 奇→偶包住写入。

**GTA 侧**（`compositor.cpp` + `.fx`）做四件事：

1. **深度测试**：把 MC 的深度换算到 GTA 的视图轴，跟 GTA 的 **reversed-Z** 深度缓冲比较 → "路灯挡住 MC 房子、房子挡住后面的车"
2. **重投影**：把这一帧从"MC 渲染时用的位姿"重投到"GTA 当前相机"。分两步：先按旋转投，再做 **6-DoF 光线步进深度重投影**（这样横移和第三人称摆动不会让方块游移或 Steve 掉出画面）
3. **重打光**：用 GTA 模糊后的画面给 MC 上光 → 夜晚街道会把方块染暗
4. 匹配 GTA 的色调/雾、边缘软化，最后把 overlay 置顶

它为此做了两件工程上的取舍：

- **位姿滞后 1 帧**：脚本读到的是 GTA "正要渲染"的相机，而屏幕上是上一帧 → `set_pose_lag()`。代码注释写明**实测 0 最匹配**（`g_poseLag{0}`，"measured: 0 matches best"），文档却写着 1 —— 这个参数是靠"金墙对比天空线"逐帧量出来的。
- **`set_camera_locked`**：飞行时相机跟着 Steve，此时**不能**重投影，否则人会飘。

### 2.3 输入：宿主主导 + 白名单式拦截

GTA 始终保持窗口焦点，**走 GTA 自己的输入系统**，两步：

```cpp
const int kDisabledControls[] = {
    24, 25, 257, 140, 141, 142, 143, 263, 264,   // 攻击/瞄准/近战
    14, 15, 16, 17, 37, 261, 262,                // 武器轮盘/上下一把
    157, 158, 159, 160, 161, 162, 163, 164, 165, // 数字键武器槽
    44, 45, 47, 58,                              // 掩体/换弹/引爆/投雷
    68, 69, 70, 91, 92, 99, 100, 114, 115, 116,  // 载具武器
};
for (int control : kDisabledControls) natives::DisableControlAction(0, control, TRUE);
```

**然后反过来用 `IsDisabledControlJustPressed/Released` 读这些被屏蔽的按键** —— 屏蔽不是终点，是"把宿主从这个键上摘下来，改由我转发"的手段：

```cpp
void forward_button(int control, const char *key) {
    if (IsDisabledControlJustPressed (0, control)) sendf("{\"t\":\"key\",\"k\":\"%s\",\"down\":true}",  key);
    if (IsDisabledControlJustReleased(0, control)) sendf("{\"t\":\"key\",\"k\":\"%s\",\"down\":false}", key);
}
// ...
forward_button(24, "attack");   // 左键
forward_button(25, "use");      // 右键
```

MC 侧 `ClientInput` 落在 **KeyMapping 层**，而且**手动补 click**：

```java
if (down && !key.isDown()) {
    KeyMappingAccessor access = (KeyMappingAccessor)key;
    access.passthrough$setClickCount(access.passthrough$getClickCount() + 1);  // ← 手动产生一次 click
}
key.setDown(down);
```

> **这和我们 `InputBridge` 里"必须自己补 `KeyMapping.set(...)` + `click(...)`"是同一个坑的同一个解法** —— 绕开 `MouseHandler.onPress` 就丢掉了它内部的 click 计数。

滚轮和数字键不走 KeyMapping，直接改槽位：`{"t":"scroll","d":±1}` / `{"t":"slot","n":0-8}` → `setSelectedSlot`。  
Tab = 枪械模式（实验），Esc 直接 `screen().onClose()`。

**关键：走动、开车、相机、视角键全部是 GTA 的，Minecraft 只是跟随。**

### 2.4 地面与方块（与我们高度同构）

- GTA 每帧在玩家周围 **40 格、160 次探测**找地面 → 发 `ground` 列 → MC 填 **barrier 方块**（用屏障而不是自定义方块，直接复用原版碰撞/渲染）
- MC 的方块变化回传 → GTA 生成**隐形碰撞 prop**，让行人和车撞你的建筑。**硬上限 400 个**，因为"GTA 在约 1500 个脚本对象时会崩"
- 坐标：**1 GTA 米 = 1 方块**；`(x,y,z) → (x, z+yOffset, -y)`；`MC yaw = 180 − heading`；pitch 取反
- `yOffset` 选得让脚下地面正好落在整格边界；**F8 重新对齐**，且"附近 24 格内有方块就不自动重对齐"（防止把你搭的东西抬走）

### 2.5 模式切换：**只有一个总开关**

F7 是唯一开关，走 `keyboardHandlerRegister` 拿到按键 → 置 `g_toggle` → tick 里消费：

```cpp
if (g_toggle.exchange(false)) { g_enabled = !g_enabled; natives::Notify(...on/off); }
const bool on     = g_enabled && g_ws.connected();
const bool hidden = IsPauseMenuActive() || IsScreenFadedOut() || IsCutsceneActive() || IsPlayerSwitchInProgress();
compositor::set_active(on && !hidden);
if (!on) {
    show_player(ped, true);           // 恢复宿主玩家
    props_clear_all();                // 清掉所有方块 prop
    mobwar_clear_all();               // 清掉所有怪物/警察
    compositor::set_screen_fx(0,0,0,0);
    std::string ignored;
    while (g_ws.poll(ignored)) {}     // ← 把积压消息全读掉丢弃
    return;                           // 注意：tick 在这里就返回了
}
```

三个细节值得记：

1. **只读按键、不做边沿检测**：`g_toggle` 是 `atomic<bool>`，`exchange(false)` 天然去重
2. **关闭时把 WebSocket 积压全部排空**，防止重新打开时"旧消息突然涌进来"
3. **它是"关掉整层"而不是"移交控制权"**：关闭后 MC 就彻底不管了

---

## 3. 性能 / 兼容性 / 稳定性影响

### 3.1 性能

| 项      | 成本                                                                                                                                             |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 帧读回    | 每帧 world RGBA8 + depth float32 + overlay RGBA8。1080p ≈ **每层 8.3 MB**；3840×2160 ≈ **每层 33 MB**，三层近 **100 MB/帧**。异步只消除阻塞，**不消除 PCIe / CPU 拷贝带宽** |
| 合成     | 每个宿主像素多算一次：深度比较 + **6-DoF 光线步进重投影** + 模糊重打光 + 色调匹配。纯 GPU 叠加开销                                                                                  |
| 强制降配   | MC 限 **120 fps**（"渲染更快只是和宿主抢 GPU"）、关云/雾/云影、关 view bobbing、关晕影、关 FOV 效果、关受伤倾斜、关菜单模糊、`pauseOnLostFocus=false`                                    |
| 窗口     | MC 窗口被 resize 成 GTA 真实 backbuffer 尺寸，且**必须先 `SDL_RestoreWindow` 取消最大化**否则 resize 被忽略                                                           |
| MC 侧世界 | void 超平坦（只有一层空气），只跑玩家放的东西                                                                                                                      |

### 3.2 兼容性

- **GTA V Legacy only**；Enhanced（DX12）**明确不支持**，作者自己列为 open question
- 依赖 **ScriptHookV**（与游戏 build 强绑定 → 游戏一更新就得等新版 SHV）+ **ReShade 6.8+ 带 add-on**
- ReShade 装不上常规代理：**GTA 会先加载系统 `dxgi.dll`**，所以只能通过 ASI loader 以 `ReShade64.asi` 加载
- 明确**未验证**：非 NVIDIA GPU、其它宽高比、其它 GTA build
- 安全约束：必须 `-nobattleye` + 只能故事模式。作者记录过一次险些出事——脚本在用户打字时抢焦点点击 landing page，触发"attempting to access GTA Online servers with an altered version"

### 3.3 稳定性（作者自述 21 条 gotcha，挑跟架构相关的）

| #  | 问题                             | 根因                                                                       | 修法                                                               |
| -- | ------------------------------ | ------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| 1  | 深度读回后颜色读回全是垃圾                  | GL 后端 depth `copyTextureToBuffer` 把读 FBO 的 read buffer 设成 `GL_NONE` 且不恢复 | mixin 在 GL command encoder 上恢复 read buffer                       |
| 6  | 切换效果时闪烁然后崩溃                    | **在 begin-effects 回调里 toggle 效果**                                        | 改用 uniform（`McActive`）门控，绝不在该回调里 toggle technique                |
| 7  | HUD 压扁、角度歪                     | MC 窗口跟随显示器尺寸（32:9）被塞进 GTA 的 16:9                                         | MC 窗口调成 GTA 真实 backbuffer 尺寸 + 先取消最大化                            |
| 8  | 方块领先 GTA 画面一帧                  | 脚本读到的是"正在准备的那一帧"的相机，不是屏幕上的                                               | 重投影到上一帧位姿（`pose_lag`）；用"金墙 vs 天空线"逐帧测量                           |
| 9  | 追车视角抖动                         | 用 `GetTickCount`（16 ms 步进）或 Python `sleep` 计时                            | 统一用 MC 的 `System.nanoTime`（Windows 上同 `QueryPerformanceCounter`） |
| 13 | 代理实体会显形                        | MC 26.3 首次网络同步会重置 Invisible，除非实体有 effect                                 | 给个隐形无限时长的 effect                                                 |
| 14 | 怪物不理会代理                        | 无敌实体永远不能被索敌，且索敌会测隐形和视线                                                   | **改成可受伤但 `hurtServer` mixin 里取消伤害**                              |
| 16 | 暂停菜单时屏幕上留着最后一帧 MC 画面           | **GTA 暂停菜单会停掉 ScriptHookV 脚本**，没人去清 overlay                              | **至今没修**，靠视频剪辑回避                                                 |
| 2  | join 时的 setup 命令匹配不到人（`@a` 为空） | Fabric 的 JOIN 事件早于玩家进玩家列表                                                | 延后 10 tick 执行                                                    |

**注意 #16**：这是"把整层开关交给宿主脚本"架构的直接代价——宿主一旦停掉你的脚本，你连清屏都做不到。

### 3.4 它做对的一件事：**离线自测**

- `host/fakehost.py`：无 GTA 时伪造宿主——喂相机和地面，再把 MC 导出的帧与"已知几何"合成成 PNG，**直接看对齐和遮挡对不对**
- `gta/tests/fakegta.cpp`：一个 D3D11 小程序（带 reversed-Z 深度缓冲、GTA 的相机约定），**把真合成器和真 WebSocket 客户端一起编译进去**，带"快速摇晃相机"模式来暴露延迟
- `gta/tests/ws_test.cpp`：对运行中的 mod 验握手/60 Hz 相机/地面列/TNT 命令/爆炸回传

作者原话："**the fake host and fake GTA let most of the work happen before the 125 GB game finished installing**"。  
—— 这也是它能在两天内做完的主要原因。

---

## 4. 对 StellarCraft 的参考价值

### 4.1 结论

**架构层不能照搬；有三块具体技术值得借鉴；另有两条是反面教材。**

依据是"核心假设相反"：

| 维度     | GTA5 示例                                 | StellarCraft                   |
| ------ | --------------------------------------- | ------------------------------ |
| 角色     | **GTA 拥有角色**（走动/开车全是 GTA 的）             | **MC 拥有角色**（原版移动/物理/背包/方块）     |
| 相机     | **GTA 拥有相机**，MC 跟随                      | **MC 拥有相机**，剑星被动               |
| 输入     | 一个总开关 F7，无"归属"概念                        | **角色归属(0x10) 与 输入归属(0x20) 双位** |
| 画面     | **深度合成进宿主管线**（宿主遮挡 MC 世界）               | **进程外 GDI 分层窗口叠图，无深度**         |
| 宿主 API | ScriptHookV（跟 build 绑定）+ ReShade add-on | UE4SS（跟版本绑定）+ 自研 overlay.exe   |
| 通信     | WebSocket(控制) + 共享内存(像素)                | 全部共享内存                         |

只狼那份 `REFERENCE_DESIGN.md` 早就点破过这点：


> "其默认行走仍由 GTA 主导，只有特定分支改由 MC 驱动，**不能照搬来满足本项目的 MC 操作要求**。"

—— 这句话被只狼和我们都各自验证过一遍。

### 4.2 值得借鉴的三处

#### A. 深度合成（最大的一块，也是我们最缺的）

我们现在 overlay 是"进程外 GDI 分层窗口无脑叠图"，**完全没有深度**。后果：你放在剑星场景里的方块**不会被墙挡住**，"叠上去"的感觉很假。这正是 `docs/DESIGN.md` 里"World 层对剑星深度缓冲做深度测试"那条，一直没做。

它的做法：MC 额外导出 **world depth（float32）** + 相机位姿 + near/far/vFOV，宿主侧把 MC 深度投到宿主视图轴去比。

**拦路虎是我们不 hook DX12。** 它是靠 ReShade add-on 拿到宿主 backbuffer/depth 的，剑星 DX12 没有等价生态。可行的适配顺序：

1. **先只导出 MC 自己的深度**（现在只发 RGBA8）——即使暂时不消费，也先把格式/带宽/时序验证掉
2. **顺着 `amd_fidelityfx_dx12.dll` 找剑星的深度资源**——剑星自带 FSR，而 **FSR 本来就需要深度输入**，说明深度确实被交给了这个 DLL。从它入手比从零 hook DX12 现实得多
3. **明确止损线：拿不到宿主深度就不要做世界层深度合成**。把 overlay 换成 DXGI 交换链 / DirectComposition **只提升画质，拿不到遮挡**——要知道这条路的收益上限

#### B. 延迟掩盖：重投影（低成本、高回报）

它把"这一帧是用什么位姿渲染的"**随帧一起发出去**，宿主侧从渲染位姿重投到当前相机；并且实测出**位姿要滞后**（脚本读到的是正要渲染的相机）。还留了 `set_camera_locked` 给"相机跟着角色"的场景。

**对我们是直接可用的**：现在 MC 帧只是"底朝上等比缩放底部对齐"地贴上去，剑星相机一动就会有同样的延迟感/错位。**只要在协议里把 MC 帧的相机位姿 + near/far + vFOV 捎上**，未来做重投影就有原料。这是**低成本的协议预留**，而且顺带解决了"剑星相机在动时 MC 画面与剑星世界对不齐"的问题。

抄的时候注意：它的 slot 描述符字段名和 flags 语义（`zZeroToOne` / `bottomUp` / `reversedZ`）是**踩过坑之后定下来的**，可以直接沿用命名。

#### C. 无游戏自测（最容易被忽略，但很值钱）

`fakehost.py` + `fakegta.cpp` 的组合，让**大部分工作在两款游戏都没装好的时候就完成了**。

我们目前每次改动都要"重编 → 你关两个游戏 → 重启 → 人眼看"，迭代一轮成本极高。而我们已经有 `tools/probe_shm.py` 在读 slot 像素——**往上一步就能做成 fake host**：伪造一个消费者，把 MC 发来的帧按给定窗口尺寸/缩放合成成 PNG，自动检查对齐、缩放、光标映射。

这个投入回报很高：以后改相机、缩放、光标坐标映射，**不用开剑星就能回归**。

### 4.3 引以为戒的两处

#### D. 单开关够用不等于双位是过度设计

它一个 F7 就够，因为它只有"透传开/关"一个自由度，而且**没有"宿主菜单要能用"这条约束**——它靠 `hidden = 暂停菜单 || 过场` 把整层关掉。

代价就是 gotcha #16：**暂停菜单时屏幕上留着最后一帧 MC 画面，至今没修**。

我们的 `MENU_OPEN` / `NATIVE_UI` 位解决的正是这个问题（"剑星菜单点不了"那一轮）。**看到它的极简设计，不要反过来怀疑我们的双位拆分**——场景不同，约束不同。

#### E. 宿主 API 的掌控力决定 roadmap 风险

它整套建在"ScriptHookV 能读相机、能屏蔽控制、能生成 prop"上，而 **ScriptHookV 与 GTA build 强绑定，游戏一更新就得等**；ReShade add-on 也是外部依赖；Enhanced（DX12）直接不支持。

我们的对应风险是 **UE4SS + 剑星版本**。启示：**把"依赖宿主内部 API"的部分收敛到一个薄层**（现在 `main.lua` + `stellarcraft_shm.dll` 基本做到了），别让核心机制建在会随版本漂移的接口上。

---

## 5. 如果要适配：优先级排序

| 优先级   | 事项                                                  | 成本 | 收益 | 备注                         |
| ----- | --------------------------------------------------- | -- | -- | -------------------------- |
| **1** | 协议里加"MC 帧位姿 + near/far/vFOV + frameSeq"，并预留 depth 层 | 低  | 高  | 为将来重投影/深度合成留原料；顺带量化带宽代价    |
| **2** | 写 fake host 做离线回归（复用 `probe_shm.py`，输出 PNG）         | 中低 | 高  | 彻底改变迭代成本结构                 |
| **3** | 调研能否从 FSR 那条路拿到剑星深度                                 | 高  | 高  | **必须先做技术验证**；拿不到就不做世界层深度合成 |
| —     | 换 DXGI/DirectComposition 提升 overlay 画质              | 中  | 中  | 只解决画质，**不解决遮挡**，要知道收益上限    |

**不建议照搬**：

- WebSocket 传控制消息 —— 我们共享内存更快，且已解决冷启动脏位/warm-up 那套问题
- ReShade 路线 —— 剑星 DX12 无对应生态
- "宿主主导角色"的输入模型 —— 与我们的核心不变量冲突
- 单一 F7 总开关 —— 会退回"剑星菜单点不了"
