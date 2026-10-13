<div align="center">

# StellarCraft

### 在《剑星》的世界里，体验 Minecraft 的玩法。

**保留剑星的世界，用 Minecraft 的规则游玩。**

> 🤖 **本项目由 OpenAI Codex 创建。** StellarCraft 是由 Codex 参与代码编写和持续迭代的实验性项目，开发目标、项目方向与游戏实测由项目维护者指导和验证。

[![Status](https://img.shields.io/badge/Status-Early%20Alpha-f59e0b)](https://github.com/Slime11123/StellarCraft)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62b47a)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric%20Loader-0.19.5-d8b36c)](https://fabricmc.net/)
[![Platform](https://img.shields.io/badge/Platform-Windows-0078d4)](https://github.com/Slime11123/StellarCraft)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[English](README.md) · **简体中文**

[项目介绍](#项目介绍) · [游戏截图](#游戏截图) · [功能展示](#功能展示) · [安装说明](#安装说明开发者预览) · [开发路线图](#开发路线图) · [源码编译](#源码编译)

</div>

---

## 项目介绍

**StellarCraft** 是一个实验性的跨游戏 Mod 工程，尝试把 **Minecraft 的玩家系统**带入 PC 版《剑星》（Stellar Blade）。你可以在 Eve 模式和 Minecraft 控制模式之间切换，在剑星的场景里使用 Minecraft 的背包、HUD、移动规则、方块和部分实体机制。

项目**没有重写两款游戏**：Minecraft Fabric Mod 负责 Minecraft 侧逻辑，剑星侧 UE4SS/C++ 与 Lua 负责游戏世界和原生对象，ReShade DX12 插件负责画面内的 Minecraft UI 合成。两个进程通过共享内存协议交换状态。

> [!IMPORTANT]
> **当前为 Early Alpha / 技术研究阶段，不是开箱即用的正式版本。** 不同功能有不同的实机验证程度，目前尚未发布通用安装包。测试前请备份存档。本项目与 SHIFT UP、Sony、Mojang、Microsoft 无官方关联；使用者需合法拥有相应游戏。

## 游戏截图

### 实机展示 · 大沙漠

<div align="center">
<a href="docs/images/2026-10-12-tnt-shield-gameplay.jpg">
  <img src="docs/images/2026-10-12-tnt-shield-gameplay.jpg" width="100%" alt="剑星大沙漠场景内的 Minecraft HUD、盾牌与 TNT 像素爆炸效果" />
</a>
<br />
<sub><strong>TNT 爆炸视觉效果与 Minecraft HUD</strong> —— 2026 年 10 月 12 日实机截图。画面中可见盾牌，但不能据此证明已成功格挡或 TNT 已对剑星敌人造成伤害。</sub>
</div>

### 在《剑星》中打开 Minecraft 创造模式背包

<div align="center">
<a href="docs/images/2026-10-12-creative-inventory.jpg">
  <img src="docs/images/2026-10-12-creative-inventory.jpg" width="100%" alt="剑星大沙漠场景中叠加的 Minecraft 创造模式背包、物品分类和快捷栏" />
</a>
<br />
<sub><strong>创造模式背包、物品分类和快捷栏</strong> —— 2026 年 10 月 12 日实际游戏画面。</sub>
</div>

<details>
<summary>展开查看 10 月 9 日的早期渲染验证截图</summary>

| Minecraft HUD 合成 | 原生手部渲染实验 |
|:---:|:---:|
| ![MC HUD 早期验证](sb-mod/reshade-addon/verification/2026-10-09-MC-hud-only.png) | ![原生手部渲染验证](sb-mod/reshade-addon/verification/2026-10-09-SB-native-hand.png) |

</details>

<sub>图片来自本地开发构建，不代表已经发布可安装成品。视觉效果截图与完整伤害、格挡验收应分别看待。</sub>

## 功能展示

以下依据截至 **2026 年 10 月 12 日** 的工程记录和用户实际游戏操作整理。“已实测”仅代表表内注明的场景，不等于全功能验收通过。

| 功能 | 状态 | 当前范围 |
|---|---|---|
| Eve / Minecraft 模式切换 | ✅ 已实测 | 普通区域使用 **F4** 往返切换控制权。 |
| Minecraft 移动与建造 | ✅ 已实测 | 行走、跳跃、蹲下、创造飞行、台阶移动、登记方块放置与状态持久化。 |
| MC HUD 与创造背包 | ✅ 已实测 | 剑星 DX12 游戏画面内可交互的创造背包、分类、快捷栏和鼠标输入，见上方实机截图。 |
| 原生像素模型与物品 | ✅ 指定场景实测 | 部分实体模型、火把/萤石照明和末影珍珠安全传送。 |
| TNT 爆炸视觉效果 | ✅ 已实测显示 | 剑星画面中可见像素爆炸粒子；对登记的 MC 方块、实体的作用已有实测。 |
| 剑星普通敌人伤害 | ✅ 部分实测 | 大沙漠普通敌人登记、实际 HP 扣减乃至死亡已有日志证据；不能推断所有武器、箭矢和 TNT 来源都已单独验收。 |
| MC 生存受击与 Eve 保护 | ✅ 普通战斗实测 | 最新测试记录 66 次原生受击事件，MC 生存血量变化，保护租约有效期间 Eve 日志 HP 保持不变。 |
| Minecraft 盾牌格挡 | 🛠 修复中 | 可举盾，但一次实测因攻击方向未验证仍然扣血；来源方向修复已构建，等待再次实机验证。 |
| 弓箭、投射物和伤害整合 | 🧪 部分实现 | 已有代码与离线回归，箭矢命中、投射物显示、TNT 对真实敌人伤害仍需联合实测。 |
| 鞘翅飞行 | 🧪 部分实测 | 用户已确认基础飞行与一次显示改善；俯冲、烟花、耐久等细节尚未全部验收。 |
| 生物、人物与手部效果 | 🧪 部分实测 | 贴图与击退修复已有用户验收；动画、模型切换等边界场景仍需检查。 |
| 稳定性与地图切换 | 🛠 修复中 | 已定位并构建修复 ActorProxy 渲染器为空的问题，重启后仍待验证；切图和游戏退出崩溃尚未确认解决。 |

**详细证据：** [最新状态](CURRENT_STATE.md) · [原生战斗整合](docs/PLAN_NATIVE_COMBAT_2026-10-12.md) · [视觉与战斗五项](docs/PLAN_VISUAL_COMBAT_2026-10-12.md)。

**说明：** 截图只证明当时的界面和视觉表现，不自动代表盾牌成功格挡或 TNT 已伤害剑星敌人；性能与功能实测均限于指定场景。

## 运行环境

| 项目 | 当前开发环境 |
|---|---|
| 操作系统 | Windows x64 |
| 剑星 | PC 正版游戏，通过 Steam 启动测试 |
| Minecraft | **Java Edition 1.20.1** |
| Fabric Loader | **0.19.5** |
| Fabric API | **0.92.2+1.20.1** |
| Java | **JDK 21** |
| 图形集成 | DirectX 12、支持 add-on 的 ReShade（开发时验证过 6.8.0） |
| 原生 Mod | 兼容剑星的 UE4SS 环境；完整构建可能需要本地生成的 SDK |
| 编译工具 | Visual Studio 2022 C++、xmake |

> [!CAUTION]
> 原生 Hook、注入和插件可能与游戏更新或其他 Mod 冲突，也可能违反适用的服务条款。请自行评估风险并备份存档。本仓库不分发游戏本体、专有游戏资源或提取的剑星 SDK 头文件。

## 安装说明（开发者预览）

**目前没有可一键安装的正式 Release。** 以下仅是开发环境搭建概要，不保证在全新电脑上直接成功。

1. 备份《剑星》存档和 Minecraft 世界，保留可恢复的原始游戏文件。
2. 准备 Minecraft **1.20.1**、Fabric Loader **0.19.5**、Fabric API **0.92.2+1.20.1**、**Java 21**。
3. 克隆工程和 MinHook 子模块：

   ```powershell
   git clone --recurse-submodules https://github.com/Slime11123/StellarCraft.git
   cd StellarCraft
   ```

4. 根据下方说明构建 Fabric Mod、ReShade 插件。完整原生游戏集成还需要匹配的 UE4SS 本地环境。
5. 按 [ReShade 插件文档](sb-mod/reshade-addon/README.md) 配置支持 add-on 的运行库、着色器和组件。文档内出现的本机绝对路径只属于原开发环境，并非通用安装路径。
6. 开发环境正确配置后，启动 Minecraft 对应实例，再通过 Steam 启动《剑星》。按 **F4** 切换模式。

如果无法编译或启动，请查看 [当前项目状态](CURRENT_STATE.md)、[架构设计](docs/DESIGN.md) 和 [Issues](https://github.com/Slime11123/StellarCraft/issues)。目前还没有经过完整验证的普通用户自动安装流程。

## 操作按键

| 按键 | 作用 |
|---|---|
| **F4** | Eve / Minecraft 模式切换 |
| **E** | MC 模式下打开或关闭背包 |
| **Esc** | 返回 MC 菜单上一级／依具体模式处理 |
| **Shift** | MC 模式下蹲下 |
| **F5** | Minecraft 视角切换（取决于当前集成状态） |
| MC 默认移动按键 | MC 模式使用配置的 Minecraft 按键 |

## 技术架构

```mermaid
flowchart LR
    MC["Minecraft 1.20.1<br/>Fabric / Java<br/>物理 · 背包 · 实体"]
    BR["共享内存协议<br/>状态 / 输入 / 事件"]
    SB["Stellar Blade<br/>UE4SS / C++ / Lua<br/>世界 · Actor · 碰撞"]
    DX["ReShade DX12 add-on<br/>HUD / 菜单合成"]
    MC <--> BR
    BR <--> SB
    BR --> DX
    DX --> SB
```

已实现的世界几何通过剑星原生对象渲染，Minecraft UI 则使用 ReShade DX12 插件在游戏进程内合成。旧的外部透明 Overlay 窗口**已不再是当前架构**。

详见 [技术设计文档](docs/DESIGN.md)。

## 源码编译

**Minecraft Fabric Mod**（Windows PowerShell、JDK 21）：

```powershell
cd mc-mod
.\gradlew.bat build
```

> 项目记录中，Windows 上的完整 Gradle 构建仍有环境相关问题，部分开发版本采用增量编译与 remap 脚本。以上命令不保证在任意新环境直接成功。

**ReShade DX12 插件**（VS 2022 C++ 工具链）：

```powershell
cd sb-mod\reshade-addon
.\build.bat
```

**剑星侧 C++ 工程**（xmake、VS 2022）：

```powershell
cd sb-mod
xmake f -m release
xmake
```

xmake 工程有可选的 SDK 配置。**仓库不包含提取的剑星 SDK**，无 SDK 时编译的框架产物也不代表已经具备完整游戏功能。参见 [sb-mod/xmake.lua](sb-mod/xmake.lua)。

## 工程结构

| 目录 | 作用 |
|---|---|
| [`mc-mod/`](mc-mod/) | Fabric Java 代码、JNI、移动/渲染及相关测试 |
| [`sb-mod/`](sb-mod/) | 剑星侧 C++/Lua、ReShade 插件、原生测试 |
| [`protocol/`](protocol/) | 跨进程共享内存协议 |
| [`docs/`](docs/) | 设计、修复方案与验证记录 |
| [`tools/`](tools/) | 调试工具、探针和回归测试 |
| `stellarcraft-mcmod-forge-源码交付包/` | 实验性 Forge 迁移源码快照，非当前主要运行版本 |

## 开发路线图

- [x] 建立共享内存通信、进程内 DX12 UI 合成
- [x] 验证 Eve / Minecraft 切换、创造背包及登记方块放置
- [x] 在指定场景验证原生实体、照明、珍珠与可见 TNT 粒子
- [x] 验证普通剑星敌人登记、真实扣血，以及 MC 生存受击时 Eve 的保护
- [x] 验证基础鞘翅飞行（部分动作与耐久仍未逐项验收）
- [ ] 实机复测盾牌朝向格挡、命中来源定位修复
- [ ] 重启验收 ActorProxy 渲染器修复，继续测试弓箭和真实敌人 TNT 伤害
- [ ] 完善模型/手部动画边界场景和生物行为
- [ ] 排查地图切换、游戏退出崩溃，补充回归测试
- [ ] 验证鞘翅俯冲、烟花、耐久及更多实体/物理特性
- [ ] 实现可重复构建、安装打包和正式 Releases

最新进度：[CURRENT_STATE.md](CURRENT_STATE.md) · [战斗整合计划](docs/PLAN_NATIVE_COMBAT_2026-10-12.md) · [视觉战斗验收计划](docs/PLAN_VISUAL_COMBAT_2026-10-12.md)。

## 已知限制

- 尚不适合普通玩家无配置安装，功能不完整且可能遇到错误。
- 部分地图切换与游戏退出路径尚不稳定；MC ActorProxy 渲染器为空导致的崩溃已有代码修复，但等待重启实机验证。
- 盾牌朝向格挡、TNT 对真实剑星敌人伤害以及部分弓箭场景仍未完整验收。
- 基础鞘翅飞行已展示，但俯冲、烟花、耐久等未全部验收。
- 完整 MC 生物 AI、水面船只物理和部分人物/手部动画尚未全部支持或验证。
- 剑星不同地区的原生材质可能影响 Minecraft 模型显示。
- 需要剑星 SDK 的编译方案，必须自行依法获取相应依赖。
- 目前不宣称支持多人联机。

## 贡献与致谢

仓库公开后欢迎通过 Issues 和 Pull Requests 交流。提交日志前请清除私人路径、令牌及存档数据。

StellarCraft 的架构受 [SkyCraft](https://github.com/chasmlol/SkyCraft) 启发，但它是一个独立的《剑星》项目。感谢 Minecraft Fabric、UE4SS、ReShade、[MinHook](https://github.com/TsudaKageyu/minhook) 及相关开源工具的维护者。

## 开源协议

请参阅 [第三方版权声明](THIRD-PARTY-NOTICES.md)，了解 SkyCraft 等上游项目的版权与许可条款。

项目原创代码使用 [MIT License](LICENSE)，第三方组件遵循各自的许可证。其他游戏、软件、素材和资产仍归原权利人所有；项目 MIT 许可证不覆盖第三方内容的权利与限制。
