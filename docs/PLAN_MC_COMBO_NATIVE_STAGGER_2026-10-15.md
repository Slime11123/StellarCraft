# StellarCraft：MC 连续攻击触发剑星原生硬直／失衡 —— 源码审查与修复计划

> 日期：2026-10-15  
> 状态：**只完成静态审查和实现计划；未改生产战斗代码、未构建／部署、未实机验收。**  
> 目标：MC 模式连续命中剑星怪物，在与 Eve 原版相当的条件下造成**真正可见且可恢复的原生硬直**。当前先处理已登记的普通 Naytiba，Boss 不扩大准入或实测范围。  
> 约束：保留现有 MC 原版伤害、盾优先、唯一原生结算、轻受击和跳劈路径；游戏测试仍按 AGENTS.md 暂停。

## 一、重要结论：不能把“若干击后硬直”直接等同 HitStun 或假计数器

原版有至少三种不同机制，必须通过 Eve 命中证据辨别用户描述的那一种：

1. **普通命中反馈**：LightAttack_NoAnim 的叠加受击，不一定打断 AI 或技能；现有 MC 轻击已接入。
2. **短时打断 HitStun**：原版 SkillResultTable 中普通轻击/强击部分结果带 HitStun，典型 Time=0.3 秒，另有 1.0/1.5 秒配置；EffectTable 的 HitStun（ID 2003000）含 BlockMove、BlockSkill，默认持续 0.1 秒，覆盖时间的创建 ABI 尚未完全认证。
3. **Groggy／失衡状态**：本机 SDK 定义 ActorState_Groggy=7、ActorState_ImmuneGroggy=16；角色动作资源含 Groggy_Start/Loop/End、IdleToGroggyShowPath、GroggyToIdleShowPath，角色数值表有 GroggyEndTime。它比简单播放一次 HitShow 更接近“进入硬直后停住一段时间再恢复”。

**未证实的部分**：连续普通攻击引起该效果，到底来自攻击连段 HitLevel、持续累积的原生计量、盾被击破引发的反应，还是 Groggy 迁移。现阶段**不能声称某个固定 N 次就是剑星原版规则**。SDK 中 CollisionGroupGauge1..5、EffectAliasArrayWhenCollisionGroupGaugeZero1..5 是后续核查线索；目前不能直接认定其为硬直槽，更不能写入或扣除这些字段。

## 二、已核实的源代码链与问题定位

| 文件 / 位置 | 已核实事实 | 改动职责 |
| --- | --- | --- |
| mc-mod/src/main/java/dev/stellarcraft/world/ActorProxy.java | MC 实体代理走 hurt → DamageBridge.forward，保留真实原版攻击结果 | 不再创建重复伤害；保留 MC 无敌帧和冷却 |
| mc-mod/src/main/java/dev/stellarcraft/world/DamageBridge.java | EV_HIT_ACTOR 的 flags 为唯一 serial，weapon 低 8 位类型，bit8 为跳劈视觉 | 不把 serial 改成硬直计数；保持协议 v6 |
| sb-mod/lua/main.lua，apply_events/damage_actor | 单次事件按 uid/serial/world/epoch 复核，计算盾/血伤害并调用原生 DLL；存在可见性和目标身份拒绝 | 维持事件消费唯一入口，不在 Lua 复制原生状态或造第二套伤害 |
| sb-mod/src/shm_bridge.cpp，sc_enemy_damage_lua | 唯一原生伤害返回后按存活及上下文请求普通/重受击 Show | 在此同一 GameThread 成功命中之后接入独立硬直决策，保留现有顺序 |
| sb-mod/src/native_combat.h | 实际伤害只走原生 stat=7 盾或 stat=1 HP；执行后复核 GUID、type、实体和 HP/Shield | 硬直不能额外调用伤害入口，不能将无伤害／无敌命中算作有效命中 |
| sb-mod/src/native_hit_context.h | 命中方向、原始 500ms、进程/地图/目标、时间、serial、上下文一次性均有门禁 | 复用这些门禁和已通过的 hit.ready，而不是单独猜测来源 |
| sb-mod/src/native_hit_show.h | 原版 Light/Strong Show 请求已有实现，但 Show 创建不等于已播放；重击实机仍只看到轻击 | 不靠多次 Show 冒充硬直；强击现有未验收保持独立问题 |
| sb-mod/src/native_hit_target.h | safe_state 拒绝 6/7/8（Down/Groggy/Airborne），并验证资源路径和标签 | 保留轻击限制；Groggy 另设状态机，不粗暴删除拒绝条件 |

参考仓库原有证据：
- docs/hit-reaction-20261012/eve-hit-baseline.json：原版命中结果、HitStun 时间、targetShow/downShow/groggyShow。
- docs/hit-reaction-20261012/native-show-contract.md：HitLevel 和完整／叠加动画条件。
- docs/PLAN_MC_JUMP_CRITICAL_NATIVE_HEAVY_HIT_2026-10-13.md：Timed HitStun 只证明了资源纯度与 Time 覆盖部分；**独立调用尚未认证**。
- CURRENT_STATE.md：2026-10-15 已安装的新盾伤比例，实机尚未验收。
- 本机 SDK：D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/CXXHeaderDump/SB.hpp、SB_enums.hpp、SBAnimation_enums.hpp。**SDK 声明不是已认证的执行入口或对象布局承诺**。

## 三、修复路线

### P0：先找到 Eve 连续命中的原生触发源（必须先完成）

1. 在当前指定 exe 版本上，离线检查普通敌人被原版 Eve 连续攻击时的 SkillResultTable/SkillTable：HitLevel、targetEffect、hitLevelTargetEffect、原生 stance 状态切换和目标免疫。
2. 追踪“普通命中 → 是否累积某个原生计量／是否因连招改变 HitLevel → 原生 Groggy 或 HitStun 创建 → 到期恢复”的函数链。特别区分 **Groggy 状态7**、短暂 **BlockMove/BlockSkill 效果**及 **Shield 变为0**；不能靠名称当证明。
3. 只使用已有离线反汇编/资源样本和只读分析。对原版实际触发阈值、不同怪物的抗打断门槛、冷却与免疫保留“未知”标记，直到有真实证据。
4. **P0 验收**：文档能够明确触发是“连段等级”还是“累计攻击”还是“盾破”，并给出对应原版调用路径及必要状态/撤销条件；否则不得把固定打击次数宣传成 1:1 复刻。

### P1：复用已存在的 MC 有效命中事件（无新扣血）

1. 仅接受当前登记普通敌人，EV_HIT_ACTOR 的主目标近战 weapon 0..4、kind=1、合法 source、serial、pid、world、levelId/epoch 和时间；箭矢、TNT、横扫附带目标暂不赋予连续近战硬直。
2. 必须从 native_combat.actor 的 **result.applied=true** 开始。盾击允许进入“有效命中候选”，但其是否增加硬直取决于 P0 原版证据；盾伤和 HP 伤害依旧只结算一次。
3. 在 sc_enemy_damage_lua 准备完有效 hit.ready 后，交由一个独立的原生硬直组件；**不要将其成功与 show.requested 绑定**，因为 Show 可能被原版拒绝；也不要因硬直失败重试伤害。
4. 优先将 MC 命中转换成原生已认证的攻击／命中强度，再让**游戏原生**判断是否跨越硬直门槛。不引入任意数值的“每 N 下硬直”作为首选实现。

### P2：独立而安全的原生硬直入口

**首选方案：** 如果 P0 找到原生 Groggy 或命中累计入口，并能认证真实 owner、效果对象、ABI、免疫、恢复生命周期，就只调用该原生入口一次，让引擎自行管理硬直时间、AI、动画、特殊怪物的拒绝和恢复。

**次选方案：** 如果原版使用连段 HitLevel 而非累计条，则按经过验证的原版连段时序／等级推进，再交给原版目标阻断判断。不得修改 target+0x1AC 抗打断门槛，也不得强行给每击 HitLevel=true。

**后备方案（仅在已证实无适用可调用计量、且取得效果执行/恢复认证后）**：新建 native_stagger.h 的**每目标短期累计器**，使阈值与 Eve 对同类怪物实测攻击节奏相匹配；仅在达到阈值时请求原生无伤害 HitStun/Groggy，不直接改 AI、速度、碰撞、HP、Shield 或内存状态。该方案是“近似机制”，**不是原版等价**。

- 计数键至少包括 hostPid、MC world、原生 levelId/epoch、Actor uid、native GUID/type、实体实例地址及生成身份；严禁只以 GUID 或指针做跨地图永久键。
- 只在有效命中后累积，每个 serial 对同一真实目标最多一次；不同目标各自计数。
- 假如 P0 确认需要连续窗口，使用原版观测到的窗口；超时自然衰减/清零。不许无证据固定写死 3/5/10 击作为“原版值”。
- 怪物死亡/卸载/替换、F4、换地图、PID 变化、重连/新世界时清理；异常后不遗留锁定状态。
- 原版已在 Groggy/Down/Airborne、强制动作、霸体及 ImmuneBlockMove(13)/ImmuneBlockSkill(14)/ImmuneGroggy(16)/ImmuneSkillCancel(17) 等状态时遵从真实机制拒绝；不覆盖其值。
- 原版效果由引擎生命周期自动撤销。须证明失效路径与恢复 AI；不得用高频 Tick 强制 set velocity=0、写 state=7、停 BehaviorTree 或无限重播 Show。

### P3：与现有受击和盾机制隔离

- 原盾优先式保持：当前有盾只降盾，本击破盾不溢出 HP；硬直的触发决策**不能**再次调用 stat=1/stat=7 或重放原版 SkillDamage。
- 普通轻击保持原版 Light 叠加；达到硬直时由原版状态机选择 Groggy/HitStun 过渡，不能“同一命中轻击 + 重击 + 硬直”三套播放相互抢占。
- 跳劈重 Show 实机尚未通过，本任务不得把其 selectedHeavy 当成功播放证据，也不得顺带修改优先级解决当前硬直。
- Boss、友好 NPC、未认证资源路径、未知版本一律不扩大准入；无法认证硬直时 **只保持原有 HP/Shield/轻受击**，不崩溃、不放开保护。

### P4：离线回归 → 构建（不替代实机验收）

必须新增原生门禁桩测试及流水线回归，至少覆盖：

| 场景 | 期望 |
| --- | --- |
| 同一敌人有效连续近战 | 不提前硬直，到原版条件时仅触发一次原生硬直 |
| 两只怪物交替命中 | 各自累计、互不串计 |
| 同一 serial 重放、同一目标重复通知 | 伤害和累计最多一次 |
| 一次仅减盾、破盾、下一次减 HP、回盾 | 盾优先和唯一伤害始终正确；是否计硬直由 P0 结论决定 |
| 免伤、被取消、未命中、空命中位置、无效方向、目标死亡 | 不推进或不非法触发硬直 |
| 目标正处霸体/免疫/Groggy/Down | 依原生条件拒绝或维持原状态，不无限延长 |
| F4、换图、卸载、GUID复用、PID变化、时间过期 | 清除积累和失效上下文，不作用于新目标 |
| 硬直到期、动画结束、复原 | 引擎撤销效果并恢复原有 AI；禁止永久定身 |
| 箭矢/TNT/MC 生物攻击 | 当前范围不增加连续近战硬直；原功能不回归 |
| 旧 exe/字节门禁变异、GameThread 不匹配 | 安全拒绝，不允许不认证的原生调用 |

离线测试先覆盖 native-combat-gate.cpp、native-combat-routing.lua、native-player-gate.cpp、已有盾优先和命中上下文测试，再运行 DLL/JAR 的相应构建和 ABI/协议校验；不得声称构建通过等于游戏中出现硬直。

**实机验收暂不执行。** 只有用户明确解除 AGENTS.md 的游戏测试暂停后，才允许由用户自己操作，在普通区域逐击观察对照：Eve 原版连续命中与 MC 连续命中分别的受击次数、硬直持续/动作停顿、恢复攻击、HP/Shield 变化。Boss 区域不在该次验收范围。

## 四、建议落点与交付条件

- 新组件候选：sb-mod/src/native_stagger.h（前提：先确定与认证调用）；配套只读门禁与原生 stub 测试，避免直接扩张 native_combat.h 的伤害职责。
- 唯一生产入口候选：sb-mod/src/shm_bridge.cpp 的 sc_enemy_damage_lua，在 native_combat::Result 已确认真实扣盾/扣血且 native_hit_context 已通过之后；main.lua 的 apply_events 保留目标登记与时间身份总门禁。
- 无需预先修改 Java 协议，当前 v6 的 weapon/serial/目标上下文足够表达逐次命中；若 P0 证明真正需要具体连段阶段，再单独设计双端版本化字段，**绝不复用 flags serial**。
- 日志建议新增 StaggerDecision：uid/guid/epoch/serial、attack kind、伤害路由、有效命中、原版门槛证据、状态前后、effect accepted/declined、到期/清理原因。只能有限频、不得输出大量原生地址。
- **完成定义**：原版触发路径已证实 + 安全生产实现 + 所有离线回归/构建通过 + 用户明确恢复测试后的普通怪物实机验收。只创建计划不意味着已经修复。

## 五、当前交付边界

本轮：**仅静态审查与写计划**。未修改 MC/SB 战斗实现；未启动游戏、未向游戏发送输入、未创建 QA 指令、未部署 DLL/JAR/Lua。用户当前未提交的所有源码改动保持原样。
