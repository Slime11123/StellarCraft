最新 2026-10-14（Timed HitStun时长覆盖机制已明确，等待原版强击只读样本）：继续离线追真实创建链，确认1BC58A0复制0x9C创建参数到实例+68；1BC64C0..1BC64EB从实例+74读取正数Time覆盖，未提供时才读EffectTable+19C默认0.1。因此原版0.3秒应写入本地创建参数+0C并由原生初始化消费，不直接修改存活效果或状态内存。

进一步发现1AD7880创建的是另一层效果登记对象（其+58存FName），不能当作5C0BD18运行实例（+58为目标owner）调用/读取，否则会错用对象类型。登记对象/运行实例/创建参数/目标owner必须分别认证。当前仍缺独立调用的完整实参、目标免疫、真实实例和到期撤销证据，未启用生产Timed HitStun、不宣称重动画修复。

新增probe_hit_resources.py --watch-hit-stun（1..45秒有界）：仅VM_READ/QUERY，校验exe/PID/base，读取当前server manager+120稀疏表、有效位/活ID/实例vtable5C0BD18、HitStun ID2003000、目标owner及entity关联，保存创建参数和时长、观察出现/消失；改变或无效样本拒绝，不调用游戏函数/输入/写入。timeField90未确认语义，不称elapsed；样本缺失不等于已证明自动撤销原因。只读工具语法通过，尚未实采这一模式。

进程核查无剑星。为补齐原版效果创建与恢复证据，已请用户自行启动剑星并停留Eve普通敌人区域、回复“已进入”，暂不攻击/不启动MC；下一步启动30秒只读采样后提示用户Eve原版强击一次。此前MC跳劈分类和7次Strong Show创建不重新测试。本轮未构建/部署/启动游戏或发送输入。当前部署哈希保持775D54714BA82F571120530CD37AFC20979F2A2086120DC36365283107189113。以下为历史记录。

最新 2026-10-14（Timed HitStun独立调用审查继续，未接入未认证API）：按剩余P2继续离线，不重复测试跳劈分类/Show创建/轻击。hit-stun-evidence.py验证采样HitStun ID2003000仅BlockMove/BlockSkill、无Stat计算、无关联Show/链效果、定时LifeType2；8项错误ID/HP计算/状态/生命周期/链效果/Show负例拒绝通过。此为资源纯度，不是调用或恢复认证，nativeTimedInvocationCertified=false。

核对当前exe中的反射注册表：SBApplyEffect native exec=269A020，26A8D50是返回UFunction的getter跳板（跳往CFD0E10），不是效果实现；不能将SDK声明、getter地址或字符串注册当成可调用效果ABI。当前native exec 269A020..269A15D只解析FName和ActorGUID然后返回，无效果创建调用，因此已排除使用此调试接口获得硬直的路线，不调用它、不改内存补救。证据effect-alias-references.txt、timed-effect-dispatch.txt。

已追到真实效果owner/实例链：effect vtable5C0BD18、目标owner+58/来源owner+60，独立alias解析1BDA730、创建/执行候选1BDA9D0、初始化候选1BDC710及生命周期函数1BC3F10..1BC55A7。原来1B171A0其实是转发客户端命中表现的队列，不能冒充服务端Timed HitStun入口；诊断标签已纠正。通用效果链还会处理其他效果、生命值和网络事件，必须完整确认实参、0.3时间覆盖、目标免疫和撤销分支后才能选独立调用，不能整段重放。目前这些尚未完成认证，未新增生产效果调用或部署。

当前安装仍Show返回值修正版775D54714BA82F571120530CD37AFC20979F2A2086120DC36365283107189113，重受击实机仍未通过。游戏进程核查无剑星，助手未启动游戏/发送输入/新增QA。下一步继续真实效果创建的Time覆盖与immune13/14/17和到期/死亡/地图撤销证明；暂不要求用户再攻击或重启。以下为历史记录。

最新 2026-10-14 21:26（返回值修正版实测仍仅轻击；Show创建已确认，补到原版HitStun配置）：用户完成攻击并明确仍然只有轻击效果。24条HitContext中13条jumpHeavy=true，7条selectedHeavy=true/showRequested=true，未出现native show creation declined。新DLL已检查原生返回指针，因此7次确实创建了Strong Show；不再把定位集中于跳劈识别、创建失败或资源配置为空。当前重受击仍未通过。脱敏战斗日志/result.json在docs/jump-heavy-20261014/test-2126/，游戏由用户操作，助手无输入。

只读PID2260当前普通角色与原生EffectTable；两个采样均拒绝0。原版HitStun行ID2003000，ActorState1=BlockMove(1)、ActorState2=BlockSkill(2)、其余0；StatType=None(0)、CalculationValue=0，Active/Loop/Deactive Show及四类关联效果数组均空；LifeType=2、默认LifeTime=0.1，而原版StrongAttack2_1命中结果显式Time=0.3。当前MC重Show没有配套这个效果。全身动画原版stopAtMove=1；243393AD..243393D5会在移动且无排除条件时清除播放记录。20A4A60读取CharacterMovement速度，角色vtable+680指向4064560（mov rax,[rcx+330];ret），20A6F40读取组件+EA8排除标志。配置证明缺少原版移动/技能阻止配套，是当前主要假设，但尚未获得命中期间动作停止时序，不能断言唯一根因。

下一步审计原版技能命中效果的独立Timed HitStun调用、当前目标免疫BlockMove/BlockSkill/SkillCancel条件、0.3秒到期移除及F4/地图/死亡清理；只允许这一无HP修改的原版效果并尊重免疫，绝不重放SkillDamage、强制改优先级或写状态内存。SDK调试SBApplyEffect没有持续时间参数，默认0.1秒，不能直接拿它冒充原版0.3秒。本轮只增加只读/离线诊断工具，未新增生产调用、构建或部署。暂不需要用户再攻击；继续离线认证配套效果入口后才制作下一候选。以下为历史记录。

最新 2026-10-14（Show返回值修正已备份安装）：用户正常退出后，进程核查确认剑星/Java均关闭。实际候选DLL/Lua事件回归及17项运动/姿态回归通过；核对候选与旧安装哈希，备份旧DLL后安装新版，候选/部署SHA256一致：775D54714BA82F571120530CD37AFC20979F2A2086120DC36365283107189113。备份路径与部署证据见docs/jump-heavy-20261014/show-return-candidate/manifest.json。协议仍v6，Lua/JAR/addon无需更换；QA文件0字节，未启动游戏或发送输入。

本次只修正原生Show空返回被误记成功的缺陷，不能宣称重动画已修复。下一轮由用户正常启动/操作，在普通敌人对比轻击和下降跳劈；读取native show creation declined与selectedHeavy/showRequested，区分未创建Show和已创建但全身动画未表现。轻击/唯一HP保持已验收结果，不重做已通过测试。以下为历史记录。

最新 2026-10-14（原生Show返回值误判已修正，候选待安装）：离线确认1C50396原版调用后在1C5039B测试RAX；2285E29返回Show指针，2285E3C返回0。现有native_hit_show::Show误声明void，invoke在原生正常拒绝时仍返回true，因此旧selectedHeavy/showRequested日志不能证明引擎创建了Show。已改为返回void*并检查非空；原生空返回记native show creation declined，保留HP且不请求第二套Show、不锁止后续命中；仅真正SEH异常记native show call faulted并锁止。普通LIGHT参数、重分支、优先级和伤害保持原样，未将其当全身动画根因或已修复。

DLL构建、2989字节变异门禁、LIGHT/HEAVY九参stub、空返回后恢复/轻击空返回/SEH异常负例、Lua唯一伤害与样式配对回归通过。候选与manifest：docs/jump-heavy-20261014/show-return-candidate/；SHA256 775D54714BA82F571120530CD37AFC20979F2A2086120DC36365283107189113，协议仍v6，仅需更换DLL。实际安装仍旧v6 DLL；PID2228剑星和PID42964 Java在运行，已请求用户正常退出，未备份替换已加载文件、未启动或发送输入。下一步退出后备份安装并核对哈希，再根据原生是否确实创建Show定位重动画接受/停止；重受击未验收。以下为历史记录。

最新 2026-10-14（下降跳劈重受击实机失败；全身动画配置补样完成）：用户明确反馈“仍然只有轻击效果”。上轮27条HitContext中13条jumpHeavy=true、2条selectedHeavy=true；两次重请求落在CH_M_NA_48、GUID1738。仅证明分类/请求已进入链路，不证明全身动作播放，重受击未验收，不能继续标为P2功能完成。保留轻击和唯一HP实现，不重复修改MC跳劈分类。

用户自行启动剑星PID2228并停留Eve，助手仅只读，无游戏输入/QA。custom-paths-2228.json与custom-slots-2228.json均读取110Show/88实际角色，拒绝0。新增诊断只读取此前采样过的角色类，排除AnimInstance误读；FName显示序号取消错误的1000000上限，保留名称块/对象槽/版本/PID校验。全身CustomAnim资源通过角色+1318数组选择，原生20F3A70验证+2248的0x70步长槽、索引、活动状态与优先级。CH_M_NA_48配置及槽存在，静止样本优先级0，排除该类配置为空；六类候选60角色中45满足完整配置、15缺方向配置，不能仅以家族路径认定每个变体可播放。custom-animation-audit.json两项空配置/空槽负例通过。

两个完整Strong动画键customPlayType=0、bodyType=0、index=0、priority=100、stopAtMove=1、retry=0。原生20F3D05..20F3D0A可按优先级拒绝，24364BC也处理播放失败；这些是确定的播放条件，不是已证明的失败根因。静止补样不能证明上轮攻击时槽状态/动画接受/模型权重。原版Strong技能表另有HitStun配置，是否是维持全身播放所需仍未认证，不重放技能伤害、不强制打断、不改原版优先级。新离线反汇编full-play-runtime.txt保存相关入口与方向选择（inverseHitDirection读取Show+70，当前上下文已提供此向量，未发现局部骨骼方向缺失证据）。

本轮只改诊断/审查工具，未改生产战斗源码、构建或部署。当前安装仍candidate-v6/manifest.json四组件。下一步只处理“原版完整Strong请求→动画接受/停止→全身权重”的执行链，定位实际拒绝点后再制作修复候选；暂不要求用户再次攻击。以下为历史记录。

# StellarCraft：MC 跳劈触发剑星原生重受击动画——源码审查与实施方案

> 日期：2026-10-13  
> 阶段：**P0/P1/P3已实施，P2候选离线通过但实机未通过；v6四组件已安装。用户仍只看到轻击，完整重受击执行链继续定位。**  
> 任务边界：在已能触发剑星怪物轻受击的基础上，让 MC 人物的有效**下落跳劈**触发剑星原生、肉眼明显区别于轻击的**重受击动画**。继续使用剑星原生角色/动画/状态系统，不用 MC 代理实体动画、贴图替代或自己制作假硬直。原来的普通攻击及唯一 HP 结算必须保持不变。

## 最新实施记录

最新 2026-10-13（用户确认轻击通过；跳劈重受击v6已实施/安装，实机待验收）：用户明确轻击已有攻击动画，按通过保留，不重查。按PLAN_MC_JUMP_CRITICAL_NATIVE_HEAVY_HIT_2026-10-13.md实施P0/P1/P2/P3：NativeJumpAttack保存不可变宿主运动快照（UUID、PID、MC世界、地图、传送epoch、时间、真实高度/速度/触地），须证明先离地上升再真实下降，样本<=250ms，原版攻击冷却>0.9且排除疾跑、流体、骑乘、攀爬、失明、滑翔/飞行等。Player.attack HEAD捕获重置前冷却；只拦截一次主目标Entity.hurt调用，在try/finally内限定伤害上下文，横扫/箭矢/TNT不继承重标记，不改fallDistance或MC最终伤害/暴击倍率。实际1.20.1 Player.attack字节码和remap注入点构建检查通过。

协议同步v6，布局仍32字节；weapon低8位类型、bit8跳劈视觉，flags仍唯一serial；元数据和伤害批量逐位匹配，未知位/重标记箭矢及TNT只拒绝视觉。一次有效命中只选择LIGHT或HEAVY。只读用户自行启动的PID52216：110Show/207动画/128条Eve剑技能、155原生实体，拒绝0；StrongAttack2_1 SkillID1001007 HitLevel=1，读取当前target+1AC抗打断门槛。MC映射到普通强击等级1、原生装备加成0，不借用Eve装备属性或强制true。目标当前门槛<=1且原有身份/线程/状态/标签条件全部满足时，六类CH_M_NA_09/11/46/47/48/49选择原版完整StrongAttack的两个CustomAnim方向键；源快照/通知/根子图与IoStore缺失子资源、原生2459E0A..2459E15 null拒绝已审计，完整Strong根无ChangeAttachTo/伤害键，不调用SkillDamage或HitStun。40/45变体路径未认证重分支，28/52原有缺证据，重请求保守降级到可允许的轻反馈；未认证者没有被扩大白名单。

JAR/DLL/addon构建、Java宿主跳跃/身份/时效/冷却/异常清理、真实Java打包字段/序号/旧版本拒绝、协议对齐、2989字节门禁变异、LIGHT/HEAVY九参数单请求stub、5重资源/通知负例、Lua唯一HP及样式配对/未知位拒绝、实际v6 DLL/Lua映射/事件与17运动/姿态回归通过。用户正常退出后，确认两游戏关闭，备份并统一安装四组件，源/部署SHA一致；QA0字节，没有助手游戏输入。候选/旧四组件/完整哈希：docs/jump-heavy-20261013/candidate-v6/manifest.json、before-install/。只读样本strong-levels-52216.json，资源检查heavy-evidence-check.json。当前DLL 5A1DEEB78082F79DB905104464A3BBAC0F8E2F9FE7C2BC1FADF0DD53CA83512F；Lua 56C6477D892AF2809AEEDE2BE8A05F8ED51BC9FD6CDA2F1B27F5102B36EA9657；JAR 5C0CC56ECCC1B81BD4BDA89DEE08EB9B1A210B15AFFA5B8997D379D5444126A5；addon BA9DF73CA0EEA576D2601B880488CC3E440A8CAF72E1659182792ACA63EC738A。

**轻击用户验收通过；重受击已实现/安装，P4实机未通过，showRequested/Heavy=true仅表示请求，不等于肉眼已播放。** 下一步用户自行同一普通敌人对比地面轻击和满冷却、不疾跑、下降阶段跳劈，读取JumpAttack及HitContext的jumpHeavy/selectedHeavy、回退原因和一次HP。此前22:29一次启动F4崩溃未证明根因；回退尝试在游戏进程检查处拒绝，未替换任何文件，后续用户确认轻击正常；不将其当重击接入证据或宣称已修复。游戏仍由用户操作，助手只读日志/修复；以下为历史记录。
## 1. 仓库审查结论（实施前审查，历史依据）

| 位置 | 已验证的当前行为 | 本任务影响 |
| --- | --- | --- |
| mc-mod/src/main/java/dev/stellarcraft/world/ActorProxy.java | ActorProxy 是 LivingEntity；hurt → super.hurt → actuallyHurt → DamageBridge.forward，保留原版攻击结算结果，自己不维持第二份原生 HP。 | 必须在这次**真实命中**上附着跳劈属性，不另起一次伤害。 |
| mc-mod/src/main/java/dev/stellarcraft/world/DamageBridge.java | forward 识别近战/箭矢；MC 32 字节 EV_HIT_ACTOR 的 flags 实际写 nextEventId()（唯一序号），weapon 写 0–5；publishHit 在同批事件写 EV_HIT_ORIGIN/EV_HIT_DIRECTION。 | 现阶段没有发送“跳劈/暴击”语义；不能把 flags 按头文件注释当成可直接覆盖的伤害标志位。 |
| mc-mod/src/main/java/dev/stellarcraft/link/Proto.java；protocol/stellarcraft_protocol.h | 已定义 HIT_CRITICAL/kHitCritical 等枚举，但实际事件使用 flags 作 serial；weapon 是单独的 u32。协议当前为 v5。 | 需要建立真正的、跨 Java/Lua/C++ 一致的攻击强度传递方式。 |
| mc-mod/src/main/java/dev/stellarcraft/client/NativeMovement.java | feedback 读剑星真实 contactFlags 与 velocityY、更新 grounded；apply() 每次把受控角色 fallDistance=0。 | 不得只用 fallDistance 判断下落跳劈；当前原版暴击判定很可能因重置而不成立。 |
| mc-mod/src/main/java/dev/stellarcraft/world/mixin/NativeServerMovementMixin.java | 玩家移动包由剑星宿主接管，服务端 setDeltaMovement(ZERO) 且 fallDistance=0。 | 服务端原版 Player.attack 的暴击条件可能丢失；需要真实宿主空中/下降快照而不是借用 MC 地面碰撞。 |
| sb-mod/lua/main.lua（hit_context、apply_events） | 依赖 uid/serial/weapon/原始时间匹配同批命中，再在伤害有效时调用 damage_actor；近战对非箭矢做原生可见性验证。 | 传播攻击等级时保留原配对和去重，不让动画成为第二次扣血。 |
| sb-mod/src/shm_bridge.cpp（sc_enemy_damage_lua、prepare_hit_context） | 原生唯一扣血完成且目标存活后，准备独立 Context，再调用 native_hit_show::request。 | 增加强度选择只影响表现；原生 HP 处理、线程和身份门禁继续作为权威。 |
| sb-mod/src/native_hit_context.h | Request.weapon 限定 <=5；命中唯一性、500 ms 原始时间、世界/角色身份、方向都已校验。 | 若扩展 weapon 编码，必须先**安全拆包**再应用旧的 0–5 检查。 |
| sb-mod/src/native_hit_show.h、native_hit_target.h | 当前只请求 Hit/Result_Hit_Stand_Sword_LightAttack_NoAnim，HitLevel=false；白名单限 8 种已采样普通怪路径、有效 UID/实体/标签/状态/线程。 | 加入新重受击分支，不能放宽原生门禁，也不能无条件把 HitLevel 改 true。 |

### 原生重受击资源的真实情况（重点）

仓库 docs/hit-reaction-20261012/eve-hit-baseline.json、loaded-hit-shows.json、eve-hit-resource-graph.json **确实存在**以下原版结果路径：

- **Hit/Result_Hit_Stand_Sword_LightAttack_NoAnim**：当前轻击的安全叠加候选。
- **Hit/Result_Hit_Stand_Sword_StrongAttack**：原版强攻击的根表现；含 4 个 SBShowAnimKey，其中 2 个完整动作受 HitLevel 过滤，另含原生子表现。
- **Hit/Result_Hit_Stand_Sword_StrongAttack_NoAnim**：强攻击的 NoAnim 分支，但当前快照中的两条叠加动作仍使用 Result_Hit_add_Light_Fw_* 资源，与 LightAttack_NoAnim 重合。

**关键结论：只把 LightAttack_NoAnim 的名字替换为 StrongAttack_NoAnim，不等于会出现重受击动画。** StrongAttack 的完整动作需要独立的原版 HitLevel/动画选路及运行时角色认证；部分具体动作文件虽带 Light 字样，也不能仅凭名称判断实际播放姿态。docs/hit-reaction-20261012/resource-index-evidence.txt 还发现多个角色专属 HitReact_StrongAtk.uasset；它们是否加载、如何按角色映射与是否安全调用仍需逐个认证，不能直接把资源列表当可调用接口。

## 2. 目标语义和非目标

- **正常近战**（地面攻击、起跳上升时攻击、攻击冷却不足）：维持现有一次 HP 扣减 + 原生轻受击。
- **有效跳劈**（MC 模式下，近战命中普通剑星敌人、玩家已离地且正在下降、完整攻击冷却，并满足原版暴击排除条件）：保持同一次 MC 原版伤害输出，仅将该次剑星表现分类为 HEAVY，要求目标播放**可见的原生重受击**。
- 同一次命中**只请求一种**表现：HEAVY 成功时不再额外请求 LIGHT。敌人死亡、无敌、特定霸体/招式不可中断、目标缺合适重受击资源时，尊重原生决策；不得强行播放、重复伤害或非法打断。
- MC 的伤害算法/暴击倍率是另一条功能线：当前 fallDistance 被清零；本任务不因追求视觉而伪造 MC 伤害，不把“识别为跳劈”误记成“原版 1.5× 暴击伤害已修复”。如需恢复倍率，应单独验证真正 Player.attack 逻辑。
- 暂不扩展首领、处决/倒地、TNT、弓箭、MC 生物对剑星敌人的交互；相关现有功能保持原状。

## 3. 方案 P0——在权威攻击点识别“下降中的跳劈”

1. **捕获有效宿主空中状态**：在 NativeMovement.feedback（已有 contactFlags、velocityY、位置信息）附近维护专门的不可变 AttackMotionSnapshot，至少有玩家 UUID、hostPid、worldId、运动 epoch、样本时间、grounded、真实垂直速度/上一次高度、飞行/鞘翅状态。只采用已认证的宿主反馈；server 线程只能读完整快照，不能读半帧。
2. **同步身份和时效**：必须属于当前受控 Steve、当前单机服务器、同一 MC 世界、同一剑星 PID/接管 epoch；建议快照时效 <=150–250 ms（与当前运动时效一致），时间基准统一。F4、世界切换、地图切换、玩家死亡、失去宿主心跳、UI 禁止战斗时清空快照。
3. **严格判定**：近战攻击 ActorProxy；宿主 grounded=false、正在实际下降（velocityY 显著为负，阈值按已发布速度单位离线校准）、非鞘翅/创造飞行、非游泳/攀爬/骑乘/失明/疾跑，原版攻速冷却充分（参照 Player.attack 的 getAttackStrengthScale；典型 >0.9）。可记录 lastGrounded→airborne 证明曾跳起；避免跳跃上升、蹲落台阶、自动小落差造成误判。站位和速度阈值基于宿主帧单位，**不能未经验证硬编码 UE 厘米数或游戏速度倍率**。
4. **绑定实际成功攻击**：推荐在集成服务端 Player.attack → ActorProxy.hurt/actuallyHurt 的同一调用栈建立仅限当前攻击的上下文（必要时用定点 Mixin + try/finally 清理，确认 1.20.1 映射/注入点后实施）；DamageBridge.forward 只读取与 source 玩家 UUID、目标 UID 对应的快照判定。不能用“上次按下跳跃键”“clientTick 最近一次跳跃”“伤害大于某阈值”替代。
5. **处理原版暴击失效**：验证 MC 1.20.1 的 Player.attack 真实 critical 分支及当前 server fallDistance=0 的后果。优先能安全捕获原版实际 crit 标志则使用它；若宿主模式确实清除了其必要状态，则**只对跳劈视觉**使用经过前述认证的宿主下降判定，日志分别标记 nativeJumpStrike 与 vanillaCritical，不声称恢复原版暴击倍率。不要取消全局 fallDistance=0 导致地形误摔或同步错误。
6. **命中拒绝**：代理伤害被原版无敌帧/冷却拒绝，或 amount 非法/为零、source 非受控玩家时，没有 HEAVY 事件。箭矢、爆炸、横扫附带命中不得继承主目标的重受击标记；每个受击目标独立决定。

建议逻辑草图（伪代码，非可直接编译的接口）：

~~~text
onAuthoritativeMeleeAttack(player, target):
    attack = captureScopedAttackContext(player, target)
    try:
        runVanillaPlayerAttack()
    finally:
        clearScopedAttackContext()

onActorProxyActualDamage(source, finalDamage):
    heavy = scopedAttack.matches(source, thisProxy)
            && validHostMotionSnapshot(player, pid, world, epoch, age)
            && playerAttackCooldownReady
            && hostAirborneAndDescending
            && vanillaExclusionChecksPass
    forwardExactlyOneHit(finalDamage, weapon, heavy)
~~~

## 4. 方案 P1——在既有 32 字节事件中传递攻击等级

**推荐最小变更：为 McEvent.weapon 的高位增加有限的攻击样式位，而非占用 flags。**

- 约定低 8 位仍是 0–5 武器类型；仅 bit 8（0x100）表示 HIT_STYLE_JUMP_HEAVY；其余高位必须拒绝。普通/箭矢/TNT 等保持旧值，不改变 MC_EVENT_SIZE=32、环偏移和 damage 数值。
- 原来的 HIT_CRITICAL/kHitCritical 枚举只是声明，**当前事件 flags 实际为 nextEventId 的唯一序号**。不得用 bit 0 直接在 flags 上 OR critical：会导致 serial 碰撞、乱序拒绝、命中来源对不上。
- DamageBridge.forward/publishHit 写入 packedWeapon；EV_HIT_ORIGIN、EV_HIT_DIRECTION、EV_HIT_ACTOR 的 weapon 需逐位一致，否则现有 Lua 配对会拒绝或错配。
- main.lua 的 ev.weapon==5 / ~=5 等比较，改为先解析 baseWeapon = packedWeapon & 0xFF；唯一来源配对仍比较 packedWeapon 全值，并校验未知位。
- shm_bridge.cpp/native_hit_context.h 在 Request 中保留 weaponType(0–5) 与 heavyRequested(bool) 的分离语义。解码时检查 kind==EV_HIT_ACTOR、近战 baseWeapon、允许的 bit8；任何其他位、箭矢/TNT 重标记、过期样本均降级为 LIGHT 或拒绝视觉，不影响已通过的唯一 HP。
- 所有对 weapon 的枚举限制、箭矢轨迹限制、可见性、测试用例必须同步改为解析后的基础武器类型。追加显式日志：eventSerial、uid、baseWeapon、jumpHeavy、hostMotionAge、classificationReason。
- **兼容边界**：这改变了原事件字段的语义，建议同步将 Java/C++/Lua/addon 的协议版本提升至 v6（布局可保持不变），全部组件一次性备份并统一安装；或实现双方明确协商的 feature bit。不得悄悄混用新 sender 与旧 consumer。

备选：若审计发现其他模块依赖 weapon 全字段原值，则改为新增专门的逐击元数据事件，并按 uid+serial+world+pid 原子关联；元数据空间不足时仍保留原有 HP 事件且视觉降级，不得重发伤害。

## 5. 方案 P2——剑星侧原生重受击表现

1. 在 native_hit_show.h 中定义受击表现选择 LIGHT / HEAVY / NONE；原生 HP 结算及 Context 准备**只执行一次**。
2. LIGHT 完全沿用当前 LightAttack_NoAnim + HitLevel=false 路线，不改默认资源/参数。
3. HEAVY 优先研究并认证原版 Hit/Result_Hit_Stand_Sword_StrongAttack 的**完整动画分支**（及其真实 HitLevel 判定）；不能仅调用 StrongAttack_NoAnim 并把 Light additive 当重动画交付。核对原版技能结果中 StrongAttack 的 targetShow、HitStun 与实际角色动画触发关系；原版结果表存在 HitStun(Time=0.3/1.0) 配置，**不等于可以再次施加伤害或在任意目标上强行设置 HitLevel=true**。
4. 延续现有 0x98 命中上下文 constructor、原版 9 参数 facade、GameThread、真实 target ControlGUID/UID、原始时间 500 ms、发布世界 seqlock、UObject 活性、标签和原生状态门禁；专门为 HEAVY 补做资源路径、子表现、方向动画、通知及副作用证明。若需要完整 HitLevel=true，必须有**当前 exe 对应的原生等级判定与目标受击状态映射**，不能给所有 MC 跳劈统一传 true 来绕过原版抗打断规则。
5. 抽查并建立普通敌人 HEAVY 资源白名单：角色 ResourcePath + 所选 ShowData/动画子图 + 方向 + 是否已加载 + 排除标签 + 状态/优先级 + 动画 Notify/PlayShowKey 副作用。现有轻击的 CH_M_NA_09/11/40/45/46/47/48/49 白名单**仅说明 LIGHT 曾被认证，不能自动升级为 HEAVY 白名单**；CH_M_NA_28/52 等未认证者依旧拒绝。
6. 原版 StrongAttack 若会调用伤害效果、技能结果、ChangeAttachTo/蓝图事件或不可认证的根子事件，应选择经证明安全的角色专属 HitReact_StrongAtk 路线（需先确认合法执行入口），**不得跳过原生表现系统直接设置骨骼、蒙太奇或虚构 API**。
7. 若该目标不支持重受击或当前霸体/死亡/无敌/倒地，降级策略是**不非法播放重受击**：可保留 LIGHT 轻反馈（前提是原版允许且没有重复 Show）或仅保持 HP 结果；日志明确 heavyRejectedReason。异常时不重试另一套伤害，更不改变已结算 HP。
8. 为每次目标命中保存本地 show 选择状态：HEAVY 已请求则不再发 LIGHT；同一个 uid+serial+hostLevel 禁止重复 Show。已有原生优先级、不可中断规则继续优先，不能为连续跳劈强制重启/循环动画。

### 极重要的验收区别

- showRequested=true **仅代表已调用原生 Show facade**，不代表完整重受击动画真的播放。
- 完整强击根 Show 的动画资源能被加载 **不代表当前目标确实进入全身 Hit Reaction 或 HitStun**。
- 本功能必须以用户观察到**明显不同于普通轻击的剑星原版重受击动作**作为最终成功标准，不能只以不同 Show 名、不同日志或音效判为完成。

## 6. 实施顺序与必需回归

| 阶段 | 修改/检查点 | 完成判据 |
| --- | --- | --- |
| P0 | MC 1.20.1 Player.attack、NativeMovement.feedback、AttackMotionSnapshot、具体攻击上下文 Mixin | 离线覆盖上升/下降/着地/疾跑/游泳/滑翔/失联/冷却不足/跨世界；仅真实成功命中打 HEAVY 标签。 |
| P1 | Proto.java、protocol.h、DamageBridge.java、main.lua、shm_bridge.cpp、native_hit_context.h | 协议对齐、32 字节结构不变；批量事件保留 uid/serial/time/weapon 全值；基础武器比较正确；旧版本/非法位拒绝。 |
| P2a | 强击根 Show/专属资源离线审计（沿用已有工具、原生 exe ABI 锚点） | 有角色级资源/状态/通知证明；强击动画区别于轻击，且未引入伤害或非预期技能副作用。 |
| P2b | native_hit_show.h / native_hit_target.h 的最小独立 HEAVY 分支 | 一次合法跳劈只调用一个安全的重受击请求；不触碰原始唯一 HP、Eve 原有原生攻击。 |
| P3 | Java/Mixin 单元测试、C++ ABI/负例测试、Lua 事件/方向/序号回归、构建和哈希 | 正例与畸形上下文、反复命中、512/序列边界、目标消失、切图/F4、标签/状态拒绝全部通过；构建与模拟单独记录。 |
| P4 | **用户明确允许恢复游戏测试后**在普通敌人区域由用户实机验证 | 地面轻击有轻动画；下降跳劈有明显重动画；同一敌人只扣一次 HP；不触发额外硬直/处决/崩溃。 |

回归用例特别检查：空手/剑/斧近战，跳起上升阶段、跳至最高点、开始下降、刚落地、从台阶跌落、满/不足冷却，疾跑攻击，附魔高伤地面攻击，箭矢、横扫额外目标、TNT，多敌人，连续跳劈，敌人霸体/死亡、F4 接管切换、世界/地图重进、不同游戏 PID、跨线程错序、低 32 位时间回绕。**任一失败不得通过放宽原生 ABI/身份/线程/时间/状态校验来伪造成功。**

## 7. 风险与避免策略

1. **最高优先风险：现在 MC 的 fallDistance 一直被归零。** 不先补宿主空中快照，所谓“MC 暴击=重受击”可能永远不触发。
2. **第二风险：StrongAttack_NoAnim 的实采 additive 动画与轻击重合。** 仅替换名字几乎无法证明“重受击”，必须查完整强击分支/真实专属强受击。
3. **第三风险：原版全身强受击受 HitLevel/霸体/动画优先级支配。** 不准强制命中等级、不准绕开原版不可中断状态。
4. **第四风险：已有协议 flags 被借作事件唯一序号，且 Lua 严格匹配 weapon。** 强度元数据必须与配对链路联动，不能悄悄覆写 flags。
5. **第五风险：本地工作区已有大量未提交修改。** 实施者先审查 git diff，只改本任务必要文件，不清空、不覆盖其他 AI/血条/Forge 工作内容，不把历史文档状态视为当前代码覆盖许可。

## 8. 交付、暂停限制及下一步

- 当前交付：**本 MD 方案**。没有直接修改战斗实现、没有构建/部署，不声称功能已实现或动画已实测。
- 遵循仓库 AGENTS.md 暂停要求：**在用户明确恢复前，不启动两款游戏、不发送游戏输入、不写新的 QA 游戏测试指令**。可以进行静态分析与离线代码/协议测试；本方案的实机验收仅作为将来得到许可后的检查表。
- 后续 Codex 实施任务建议：
  1. 先确认 Player.attack 的真实映射注入点和完整 MC 服务端跳劈判定，解决 fallDistance 归零导致的识别缺失；
  2. 再做 packedWeapon/event 传递并加 Java/C++/Lua 协议负例测试；
  3. 再针对原生 StrongAttack 完整动作进行安全认证、只接一个纯表现调用；
  4. 仅在用户授权恢复游戏验证后进行实机观察、记录 Show 请求与真实播放结果，必要时回滚。
- 资源证据以当前目录中的 docs/hit-reaction-20261012/native-show-contract.md、eve-hit-baseline.json、eve-hit-resource-graph.json、loaded-hit-shows.json、resource-index-evidence.txt 以及 docs/hit-reaction-20261013-*/manifest.json 为准。原生 exe RVA/指令锚点仅适用于文档中已认证版本；更换游戏版本必须重新验证。

**最终验收口径：有效 MC 下降跳劈 → 一次原生伤害 + 原版明显的强受击动画；普通攻击 → 原有轻受击。两者不得串档、重播扣血或破坏原生游戏状态。**
