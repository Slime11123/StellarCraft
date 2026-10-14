# StellarCraft 实现方案：MC 攻击触发剑星原生敌人血条

> 日期：2026-10-12  
> 状态：**已审阅当前仓库，待 P0 确认原生 UI 入口；未实施、未构建、未实机验证。**  
> 目标：MC 人物攻击剑星敌人后，使用**剑星游戏自带的血条**，显示在敌人头顶；敌人退出原生战斗模式或死亡后隐藏。  
> 约束：本任务只产出计划；遵守 AGENTS.md 当前游戏操作暂停，不代用户启动/操作游戏，不进入首领模式。

## 1. 预期结果和硬约束

1. **血条必须是《剑星》原生 UI**，颜色、材质、动画、盾条、数值更新均由游戏自身控制；不得用 MC NameTag、BossBar、Fabric HUD、ReShade/DX12 画一个仿制血条。
2. MC 模式下，**经校验且实际扣血成功**的近战命中使对应敌人的原版头顶血条显现/刷新；后续箭矢、TNT 沿用同一机制。被游戏拒绝的伤害（无敌、越界、命中无效、协议陈旧）不能无故弹出血条。
3. 血条锚定对应**真实剑星角色**，跟随头部/原版 Gauge Socket、场景遮挡及原版距离规则，不关联透明的 MC `ActorProxy`。
4. **退出战斗即隐藏**：依据剑星真实 AI/战斗状态退出事件或已验证的状态查询，不能靠“距最后攻击 N 秒”代替退出战斗判断。
5. **死亡立即隐藏**：HP 到 0、原生死亡状态、敌人注销/对象失效时取消展示；不可出现尸体上方的残留条或重刷幽灵条。
6. Eve 模式、原版 Eve 攻击、Boss HUD、锁定/解锁、过场/菜单等仍由剑星原生逻辑管理。不可破坏既有 MC→剑星扣血比例、身份门禁、无敌、盾牌、受击动画项目。
7. 实现优先级：先普通敌人近战，再箭矢/TNT；Boss 仅做静态分析和离线测试，未经用户许可**不做实机测试**。

## 2. 本轮源码审查结论（区分事实与待证）

| 位置 | 已确认行为 | 本任务意义 |
| --- | --- | --- |
| `mc-mod/src/main/java/dev/stellarcraft/world/ActorProxy.java`、`DamageBridge.java` | MC 原版实体伤害经 `DamageBridge.forward()` 转为 `EV_HIT_ACTOR`，带 `uid`、原始伤害、武器类型和事件序号 | 复用已有攻击事件，不增加平行伤害来源 |
| `sb-mod/lua/main.lua`，`apply_events()` | 检查世界、MC 控制位、事件序号/遮挡后，调用 `damage_actor()`；`damage_actor()` 返回是否成功及真实扣血量 | **唯一优先接入点**：只有成功伤害才发起原生 UI 通知 |
| `sb-mod/lua/main.lua`，`mirror_actors()` | 以 GUID、类型、UE 对象身份登记普通敌人；缓存/发布频率约 5 Hz，`gActorsByName` 会重建；目前 `flags=1`（Boss 加 bit2、无敌加 bit4） | 不得持久保存裸 UObject；当前没有发布真实“正在战斗”位 |
| `protocol/stellarcraft_protocol.h` | 已定义 `kActorInCombat = 1u << 3`，但当前敌人发布没有设置它 | 可供诊断或后续同步，**不是原生 UI 显示开关**；本功能不必先改协议 |
| `sb-mod/src/native_combat.h` | `native_combat::actor()` 校验版本、游戏线程、GUID/类型/HP，调用游戏原生数值入口并重查死亡/剩余 HP | 目前是原生数值/死亡链路，**未证明会发布游戏 HUD/AI 受击通知** |
| `sb-mod/src/damage_applier.cpp` | 仍是带 TODO 的旧骨架 | **不得改这里另起一套伤害/UI 管线** |
| `sb-mod/src/SDK/SB.hpp` | `ASBCharacter::IsBattle()`、`GetLockOnTargetCharacter()` 已在 SDK 声明 | 可作为原生战斗/目标查询候选，仍需验证具体对象、调用安全与真值语义 |
| `sb-mod/src/SDK/WB_MainHUD_EnemyStateBar.hpp` | 原生 `UWB_MainHUD_EnemyStateBar_C` 有 `ProgressBar_HP`、`HpValue`、`MaxHPValue`、`FadeOut`、`EventNotification(FString)` | **普通敌人血条候选**；SDK 声明并不证明它一定就是头顶血条 |
| `sb-mod/src/SDK/WB_MainHUD_EnemyInfo.hpp`、`WB_MainHUD_Master.hpp` | 原生 `EnemyInfo` 含敌人 HP、Boss HP、盾值以及 `FadeIn/FadeOut`，由主 HUD 引用 | 必须区分“头顶普通怪血条”和“Boss/屏幕固定位置血条” |
| `sb-mod/src/SDK/SB.hpp`，`FSBEffectTableProperty` | 存在 `bNotVisibleHPBarWhenDamage` 配置字段 | 可作为追踪伤害引发 UI 的线索，**不得直接修改或硬清这一位** |
| `docs/PLAN_MC_HIT_REACTION_2026-10-12.md` | MC 命中缺原生受击动画的调查尚未完成；完整技能命中流程可能重复 HP 和表现 | 本方案不复用未验证的整条 `FSBSkillHitResult` 处理链 |

**关键缺口**：SDK 找到了原生血条控件和原生战斗查询，但**尚未证明“Eve 命中 → 哪个原生事件/管理器 → 头顶血条出现、更新、隐藏”的具体调用链，也未确认 `IsBattle()` 足以单独表示敌人已脱战**。所以此时不能凭函数名直接调用 `EventNotification("Show")`、`SetVisibility` 或猜地址/ABI。当前没有可以声称已经可用的血条显示函数。

## 3. 方案选择

**首选：复用剑星现有“受伤/战斗状态 → 原生血条 UI 管理器”的通知链。**

- 保持现有 `damage_actor()` 作为**唯一 HP 结算入口**；在成功扣血后，将当前真实敌人的经过验证的原生身份送入一个**仅负责 UI/战斗通知**的桥接函数。
- 原生 HUD 自己创建/复用 `EnemyStateBar`、选取头部锚点、读真实 HP、播放进入/退出动画；桥接层不创建自绘血条，不每帧直接写 `ProgressBar_HP.Percent`。
- 血条的隐藏由原生 AI/战斗状态以及死亡事件驱动。MC 侧仅提供“我攻击了哪个真实敌人”的可信一次性来源，不伪造锁定目标、不强制 Eve 出招。
- 若原版血条是由“进入仇恨/战斗”触发而不是受伤通知触发，应优先找到原版**受击引发敌意/进入战斗**的安全通知点；切勿直接强行篡改 AI 状态位。
- **不支持先绘制一条外观接近的假血条作为最终降级方案**。若原生入口无法确认，应保持旧功能不变，并报告 P0 阻塞。

## 4. 分阶段执行方案（Codex 实施顺序）

### P0：只读确认原生 UI 的真实事件链（上线阻塞项）

1. **明确用户说的头顶血条是哪种原版控件**。从 SDK/资源/原版表现找出普通敌人命中时对应的 UMG/Widget、其拥有者、Gauge Socket/头部定位及距离/遮挡；排除屏幕固定的 Boss HUD、锁定准星及离屏警示 UI。重点从 `WB_MainHUD_EnemyStateBar`、`WB_MainHUD_EnemyInfo`、`WB_MainHUD_Master` 与相关 UObject 继续向调用方追踪。
2. **静态核对调用链**：通过当前版本游戏 exe（版本门禁见 `native_combat.h`）、UI 消息派发函数、原生效果配置、事件标签/参数，找出“Eve 真实命中→显示/更新/隐藏”的完整入口及其调用线程、参数、对象拥有者。单凭 SDK `EventNotification(FString)` 不能猜出事件名。
3. **核对两条条件链**：①原版命中通知是否直接显示 UI；②是否必须同时触发 AI 仇恨/进入战斗。对照 `ASBCharacter::IsBattle()`、AI 控制器原生状态、角色生死/HP 状态，确认原版如何判断“脱战”以及哪些状态不该显示。
4. **验证副作用**：候选通知必须是“UI 刷新/展示/敌意通知”而**不是**重新执行技能伤害、盾值、硬直、动画、掉落、仇恨重置等整套受击流程；同时确认重复通知幂等。
5. **验证可安全调用**：当前 exe 指纹/模块基址、真实地址、Win64 ABI、GameThread、UObject 生命周期/GC、安全引用、世界与角色身份。若是 UE4SS 反射函数，先验证 UFunction 名称、参数与真实行为；不能由声明直接推断地址。
6. 只有在用户解除当前游戏操作暂停后，才安排**用户自行操作**的普通区域 Eve/MC 对照观察及只读日志；不由助手启动游戏、发送输入，不用 Boss 场景作为验证入口。
7. P0 交付：记录定位证据、原版血条资产/类、事件签名、显示/脱战/死亡的原始条件以及负面副作用审核。证据不足**停在 P0**，不进生产 DLL。

**P0 通过标准**：确认“普通敌人原版头顶血条”所使用的真实 UI 对象、可信显示/隐藏/刷新条件，以及可单独调用且不会造成第二次伤害的 API/消息路径。

### P1：MC 成功命中 → 剑星原生 UI

修改范围建议（只针对已定位并认证的入口）：

- `sb-mod/lua/main.lua`：在 `apply_events()` 经世界/模式/序号/身份/遮挡校验，并且 `damage_actor()` 返回 `applied == true`、`nativeDmg > 0` 时，触发**一次**受击 UI 请求；保留原有 HP 入口和伤害缩放，不改 `DamageBridge.forward()` 的消息结构。
- `sb-mod/src/shm_bridge.cpp`：若需 Lua→原生调用，增加专用、窄接口，例如 `sc_enemy_health_ui_lua`（**占位命名，不是现有导出**），输入只含已验证的敌人 GUID/类型、预期当前 UE 角色地址、世界代次和动作类型；不得接收任意游戏内存指针或字符串事件名。
- 新增 `sb-mod/src/native_enemy_health_ui.h` 或同级最小模块（建议）：只在 GameThread，按 `native_combat` 同等级版本/身份门禁重新解析活对象；调用 **P0 已证明的原生 UI 通知入口**。出错拒绝，不修改游戏 HP/AI 内存，不通过另一套 `TakeDamage`/SkillHit 补发命中。
- 不对 P0 已知不安全的 `FSBSkillHitResult::Execute` 或完整 `ApplyStatDiff` 执行二次调用；此前受击动画修复是独立任务，不能让 UI 支线产生第二次动画/伤害。

伪代码仅描述业务顺序（不是可直接编译的 ABI）：

~~~text
if mc_control && world_valid && target_unique && event_fresh && hit_visible:
    result = apply_native_hp_once(existing_damage_path)
    if result.applied && result.before > result.after:
        request_native_enemy_hp_ui_show_or_refresh(
            identity=result.verified_target, kind=MC_HIT)
# native UI decides widget creation, placement, animation and reading real HP
~~~

**表现时序**：有效 MC 命中即可触发原生血条，不必要求玩家按剑星锁定键。若 `IsBattle()` 初始仍为 false，必须通过 P0 原版机制确认命中通知与进入战斗的正确关系，不能用长时间硬置 `true` 来“维持血条”。

### P2：战斗结束/死亡 → 原生隐藏

建立每个真实敌人的**轻量 UI 活跃记录**（仅键，不持有裸 UObject）：`(worldEpoch, nativeGuid, nativeType, actorGeneration)`；死亡/注销即时撤销。

- **战斗结束**：使用 P0 证实的原生退出战斗事件（优先）或经验证的 GameThread 战斗状态查询（如 `IsBattle()` 仅在确证语义正确后使用），一旦该敌人不再战斗，调用原生 UI 的 Hide/Release/Reset 通知；无额外“最近被攻击”保活计时器。
- **死亡**：`result.after <= 0`、原生死亡/死亡状态、GUID 注销、角色失效任一确认，立即隐藏并清除记录。死亡/注销后不得为了藏条解引用已经销毁的 UObject；使用原生管理器支持的按安全键撤销接口或该 UI 原本的生命周期。
- **地图/场景切换**：世代号变更、真实对象重生替换、MC 控制权失效、卸载/断连：丢弃旧记录，防止同 GUID/地址复用；关闭属于桥接新增的原生 UI 请求，不影响剑星自己正在管理的其他 UI。
- **模式切换**：MC→Eve 不允许将 Eve 原生可见血条强制隐藏；只清理桥接拥有的请求/引用，恢复游戏原生 HUD 控制。
- **多敌人**：每个 GUID 独立；A 脱战/死亡不关闭 B 的血条；不把最后一次命中的敌人覆盖为唯一全局对象。
- **失效/异常**：不支持的 exe、非 GameThread、身份不符、HUD 未构造、暂停菜单/过场或读档中应安全拒绝或交由原生 HUD 策略；不要直接写 UMG 可见性强开。若 P0 发现原生 UI 自带 FadeOut，尊重其动画，但死亡不得残留可见僵尸条。

状态图（**示意，最终必须匹配原生状态语义**）：

~~~text
UNTRACKED
  └─ verified MC hit + native HP decreased ──> NATIVE_UI_ACTIVE
NATIVE_UI_ACTIVE
  ├─ more verified hits ──> native refresh, stay active
  ├─ native combat-exit ──> native hide ──> UNTRACKED
  ├─ native death/despawn ──> native hide/release ──> UNTRACKED
  └─ world epoch invalid / MC->Eve ──> release bridge request only
~~~

注：若原版“命中显示”和“战斗中显示”是分离事件，应遵从原版 UI 状态机，而不是强塞上述示意状态。

### P3：回归、隔离与验收

**不启动游戏的离线用例**（当前暂停期间允许）：

| 用例 | 预期 |
| --- | --- |
| 一次有效近战 HP 下降 | 产生一次 UI 请求，不发生第二次伤害 |
| MC 序号重复、错误 worldId、MC 非控制模式 | 0 次新增 UI 请求 |
| 敌人无敌/伤害 0/遮挡/原生拒绝伤害 | 不凭空新显示血条 |
| 目标 GUID/type/address 不唯一、无效、过期 | 拒绝，不能显示到别的敌人身上 |
| 连击多次命中同一敌人 | 使用原生刷新/复用，不叠加重复 Widget |
| 敌人进入战斗后退出、AI 失去敌意 | 原生事件触发 Hide，未命中不意外保活 |
| 敌人被 MC 击杀、其他来源死亡、对象注销 | 立即释放；不解引用死亡对象 |
| 多敌人 A/B、重生同 GUID、换地图、F4 往返 | 生命周期隔离；不影响 Eve 的原生 UI |
| 箭矢/TNT 多目标、多个事件连发 | 各目标独立显示，原有 HP 换算与伤害去重不变 |
| 缺 UI、错误线程、游戏版本变化、菜单过场 | fail-closed，不崩溃、不强显 |

**以后由用户恢复游戏操作后安排的实机验收**（当前不得执行）：

1. 普通敌人：Eve 原版命中血条位置、样式、出现和淡出，与 MC 近战有效命中对比；确认是同一套剑星材质/动画，不是屏幕上额外叠层。
2. MC 空手/剑/工具近战：有效伤害立即出现、HP 比例与敌人真实 HP 一致；连续攻击更新；战斗期间按原版规则保持。
3. 敌人停止追击/原版确认脱战：血条消失；同一敌人重新受击可再次出现。
4. MC 致死、TNT 击杀、敌人被其他来源杀死：血条按原版死亡表现关闭且不残留；附近其他敌人不受影响。
5. 遮挡、远离视野、相机移动、多人围攻、F4 切换、读档/地图转换、原版 Boss HUD 互不干扰；**Boss 实机需用户另行解除限制**。
6. 日志只记录 `eventSerial / worldEpoch / guid / nativeHpBeforeAfter / nativeBattle / uiAction / reason` 等最低必要信息；不开无限每帧日志，不写用户认证/登录信息。

## 5. 关键风险与决策门

| 风险 | 处理 |
| --- | --- |
| 只发现“屏幕中央/Boss 血条”而非头顶血条 | 继续寻找真实头顶资源；不得用错误 Widget 冒充实现 |
| 游戏原生 UI 只接受真实技能 Hit/AI 仇恨事件 | 定位“纯 UI/安全仇恨通知”；禁止重放整条伤害技能导致重复 HP |
| `ASBCharacter::IsBattle()` 不适合作为脱战判据 | 改用 P0 确认的 AI combat/aggro 真实事件；不采用任意超时 |
| 原版 `bNotVisibleHPBarWhenDamage` 隐藏规则 | 保留配置语义；不可一刀切清除该位 |
| 绕过原版 UI 管理器改 `UWidget` 可见性或属性 | 不接受；会与 UMG Tick、动画/对象释放竞争并可能损坏 Eve UI |
| UObject / GUID 重用、跨线程、死亡后回调 | 严格按 worldEpoch + 身份 + GameThread 重查；故障安全拒绝 |
| 当前受击动画 P0 尚未结束 | UI 项目独立实现，不借动画分支增加副作用或阻碍现有调查 |

## 6. 文件改动清单（建议，不是已修改）

1. **首先生成证据**：`docs/enemy-health-ui-20261012/` 下存 P0 原版 UI 对象、回调/状态语义、ABI 与负面副作用核对结果（待实施）。
2. `sb-mod/lua/main.lua`：只在已确认的有效命中后派发原生 UI 请求，管理当前世界身份与 UI 请求生命周期。
3. `sb-mod/src/shm_bridge.cpp` + `sb-mod/src/native_enemy_health_ui.h`（按 P0 结果决定是否需要）：小型、只处理已认证原生 UI 的窄接口。
4. `sb-mod/tests/`：GameThread/身份/去重/脱战/死亡/多敌人和跨地图离线回归；保留已验收的所有战斗用例。
5. `protocol/stellarcraft_protocol.h` 与 MC Java **优先不改**；仅当 P0 证明确需附加数据且现有事件不能承载时，提出向后兼容的同步协议变更。
6. 根据实际执行状态更新 `CURRENT_STATE.md`、`plan.md` 和本文件的 P0/P1/P2/P3 证据，严格区别“静态定位 / 离线测试 / 构建通过 / 实机通过”。

## 7. 交给 Codex 的执行要求

- **先完成 P0 再写生产代码**。明确记录实际原生“头顶”UI 资产、真实显示/刷新/隐藏入口和战斗结束判据；证据不足就说明阻塞，不猜参数、事件名或跳转地址。
- 最小改动、现有伤害路径唯一、保留现有构建/部署与实机暂停规定；不清理用户的其他未提交改动。
- 仅在用户授权恢复后进行游戏操作和普通敌人实机验证；不做首领实机。
- 完成时提供：改动清单、验证命令及输出摘要、已确认事项/未验证事项、原版与 MC 对照证据。不能把“SDK 有字段”“编译通过”写成“原生血条已实现”。

---

**当前结论**：项目已有可复用的有效 MC 命中链路，也已发现剑星 SDK 的敌人原生血条及原生战斗查询候选。最合适的架构是**在真实 MC 命中成功后通知剑星原生 HUD，并让剑星原生战斗/死亡状态负责自动隐藏**；但展示入口和“头顶”关联仍需 P0 定位，当前未编写任何功能代码，也没有宣称已经修复。
