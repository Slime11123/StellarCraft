# StellarCraft 修复计划：MC 攻击剑星敌人缺少原生受击动画

> **文档日期**：2026-10-12  
> **项目根目录**：`C:\Users\StickySlime\Documents\Codex\Projects\StellarCraft`  
> **状态**：2026-10-13 用户确认普通轻击已有受击动画，按通过保留；后续跳劈重受击独立计划已实施v6候选并安装，实机待验收。
> **范围**：MC 模式下，MC 玩家使用空手、剑、工具、弓箭及 TNT 攻击剑星普通敌人时的原生受击表现。

## 1. 问题描述与目标

最新 2026-10-13（用户复测无效果：地图编号漏发布已修复/安装，仍待实机）：用户报告没有受击效果。14:21–14:22实机日志确认原有HP正常扣减，但存活命中均在prepare_hit_context拒绝：host not in MC combat state，ready=false/showRequested=false；死亡命中按预期拒绝。源代码证明SbState.levelId此前没有任何玩家发布写入，因此新增非零地图门禁始终不满足，尚未到原生Show入口，不能归因于动画资源或优先级。已补Lua native_level_id：对有效当前UWorld完整名称计算稳定32位哈希，缺失/失效世界返回0；Lua私有第13参数传入，DLL在同一玩家seqlock内发布levelId，退出游戏状态清零。保留C导出sc_write_player原ABI（内部write_player_frame接收地图字段），不改协议v5布局，不删除非零地图/状态校验。拒绝日志区分host map identity unavailable。

DLL构建通过；真实Lua发布参数回归验证有效/稳定地图、地图变更及空名称/失效世界；实际DLL/Lua验证地图ID的两次发布、失效/旧调用清零、偶数seqlock和F4/UI位保留；17运动/姿态、Lua移动与原有伤害/唯一上下文回归通过。核查两游戏已关闭后备份并安装配套DLL/Lua，源/部署哈希一致；JAR/addon保持上一轮v5，QA 0字节，没有游戏输入。候选、manifest、旧二组件、仅战斗日志在 docs/hit-reaction-20261013-level-fix-v5/。当前DLL A93650C529FE70A89EBA2B8EC000E0569F1A53302E9E771A800AD7958B40C453；Lua A25DAF38A15E8FF92E21B08EDDD3BB4EAE72EE402AC6514D7DCE567A56B8A338。**只确认发布缺陷已修复并离线通过，动画可见性仍未通过；本次失败测试没有发出原生Show调用。** 下一步由用户正常启动两游戏，用剑非致死轻击普通敌人，读ready/showRequested/reason并观察动画；若进入后续拒绝，只根据新证据定位对应条件，已有HP/资源结论不重开。以下为历史记录。


最新 2026-10-13（普通敌人叠加受击接入并安装，实机待验收）：按用户要求，删除当前受击计划及plan.md中相关模式待办/反复范围提醒，当前只处理普通敌人。新增native_hit_target.h、native_hit_show.h及精确指令锚点：已验证的唯一HP扣减成功且目标存活后，复核GameThread、UID/原生实体/ControlGUID、原始500ms时效、八种已采样角色资源路径、实时UObject槽/删除标记、空ShowKeyTagMap、原生状态6/7/8与无敌状态。任意标签（包括零计数但仍存在的标签）、未认证路径或替代状态均拒绝表现，保留原有HP。通过原版1C49290构造0x98上下文，保留不存在的骨骼/骨骼局部位置/来源ControlGUID/标签默认值，仅写真实来源、目标位置、方向、目标ControlGUID；认证默认零向量和类型1的vtable入口。使用原版9参数facade 22857E0请求Hit/Result_Hit_Stand_Sword_LightAttack_NoAnim，HitLevel=false，只允许原生叠加表现并保留优先级，不重放伤害/HitStun/附件根资源。showRequested表示发起请求，不等于动画已经可见；异常锁止后续表现，不回滚或重复HP。

DLL构建、2926字节变异拒绝、0x98构造/9参数stub、原生状态/标签/路径/活对象/错误线程负例通过；补正快照校验的state通知字段与标签“存在即匹配”语义，8个负例通过。Lua伤害/上下文与移动回归、协议布局、实际Java环和DLL/Lua解码、17组运动/姿态通过。两游戏均关闭后备份并一起安装协议v5的DLL/Lua/JAR/addon，源/部署哈希一致，QA仍0字节；未启动游戏/发送输入。候选、四组件哈希和旧版本完整备份见 docs/hit-reaction-20261013-additive-v5/manifest.json、before-install/。**普通敌人叠加受击已实现/安装，但尚未实机验收，不能称动画已修复。** 下一步用户自行普通启动和测试剑击，读取HitContext的showRequested/reason与唯一HP变化，再验证箭矢/TNT/工具。当前CH_M_NA_28/52缺已认证叠加动画，带标签角色保守拒绝；完整硬直等级与打断未实现，不将其当作叠加效果的交付结果。以下为历史记录。


最新 2026-10-13（只读补样完成）：用户自行启动剑星PID52836，助手仅只读采样。noanim-character-tags-52836.json 有93个怪物角色/110表现/207动画，拒绝读取0，确认FName.Number显示序号限制修正有效；实际生成名称接近INT_MAX。93角色涉及10种资源目录，2个CH_M_NA_09角色带InvisibleHydra标签，未发现本候选排除的特殊NPC标签。新增hit-reaction-targets.py逐角色解析原版NoAnim启用/HitLevel=false/要求与排除标签、相对动画路径及通知；78个角色的叠加子图证据完整，15个排除（1个剧情对象空路径、CH_M_NA_28的10个及CH_M_NA_52的4个缺已加载叠加动画），不把未采到动画说成资源不存在。4个特殊标签、损坏快照、伤害通知共6个负例拒绝通过，证据ordinary-target-audit-52836.json。此为资源/通知证据，未证明角色当前可受击状态、骨骼上下文或生产调用安全，productionInvocationCertified=false。没有Show/游戏输入/部署，动画仍未修复；暂不需用户再次攻击。下一步用该范围完成原生上下文和运行时目标/状态门禁，再接唯一纯表现调用；CH_M_NA_28/52保持拒绝直到其动画得到认证。以下为历史记录。


最新 2026-10-13（命中来源/时效 v5 候选与只读补样）：用户明确恢复普通区域只读补样，自行启动/操作剑星；助手不启动/退出、不发送输入、不写 QA 指令。P1 已补齐原始逐击位置、低32位原生时钟及已确认箭矢命中轨迹，捕获后跨线程传值，不再用消费时的角色位置冒充原始来源。协议 Java/C++/Lua/ReShade 同步升为5，布局不变；类型9/10与原命中成批发布，空间不足只保留原HP事件，缺失/不匹配/超500ms上下文仅拒绝表现，不改变既有HP。箭矢方向来自实际确认轨迹，TNT保留原爆心/伤害。新JAR、DLL、输入addon构建及协议、真实Java事件环、真实DLL/Lua原始时间位/回绕/单次消费、17组移动/姿态、Lua路由、232字节门禁与时效/箭矢负例通过。四组件候选/哈希见 docs/hit-reaction-20261013-context-v5/manifest.json，必须一起安装；**尚未部署，没有任何Show调用，动画仍未修复/实机验收**，安装版仍为214611。

只读PID51772快照 noanim-tags-notifies-51772.json 得110表现/207动画，拒绝读取0。确认 NoAnim 两个叠加动作排除 AdditiveNoPlay/NoNormalAttShow/EmilShop_Flag_1st；Emil两子表现分别要求NoNormalAttShow、EmilShop_Flag_1st，Dororong要求Check_Dororong。离线证明原生243E1E0检查角色+3128及Show实例+140的标签并保留战斗条件；E6C4D0 map元素步长0x14。普通敌人的实际ResourcePath/标签仍待补样：两个带characters快照有331/92拒绝，不能当资源缺失证据；已发现诊断FName.Number的1000000限制不适用于接近INT_MAX的合法生成名称，移除此显示序号上限，地址/对象身份门禁保留，修正后实采尚待验证。PID51772已退出，旧PID采样被安全拒绝，不据此断言崩溃。下一步补读普通敌人后完成NoAnim目标条件/通知认证、原生0x98上下文转换及唯一纯表现调用；P0/P2仍未完成，完整HitLevel映射未完成。以下均为历史记录。


### 最新实现记录（2026-10-13：P1命中上下文）

新增 `native_hit_context.h`，在已有 HP 结算成功且目标存活后，准备当前目标 UID、
原生 GUID/type/ControlGUID、MC进程/世界、SB地图、事件序号、来源/目标位置与归一化方向。
`Identity` 仅补充已经验证的原生实体地址，身份门禁没有放宽；扣血后再次验证同一实体并只读
检查当前 HP/无敌状态。目标位置来自当前 exe 已认证的 `1A30C90` accessor 对应数据，
并不调用该 accessor。MC/SB状态与目标表使用 seqlock 检查，拒绝暂停/切图/非MC控制、
失效心跳、UID歧义、死亡/无扣血/身份更换、无效/零方向与重复上下文。

Lua 近战使用当前受控角色原生位置；TNT沿用已有MC爆心转换。箭矢方向未认证时不生成上下文，
仍按原有通道扣血。DLL私有函数可选参数扩展，公开v4协议及MC源码/JAR未改。
上下文当前使用消费帧，尚未携带MC原始逐击时间，不能把共享帧新鲜视为命中本身新鲜；
原始来源/时效与箭矢数据仍需完成。**上下文准备不是动画播放**，没有原生Show/效果调用。

验证：DLL构建通过；232处战斗/位置字节变异拒绝、上下文唯一事件/身份/位置/死亡/世界负例通过；
557处玩家/效果门禁变异通过；真实Lua路由的近战/TNT方向映射、去重、遮挡、旧世界和箭矢排除通过。
候选及manifest存于 `docs/hit-reaction-20261013-context-p1/`，未安装；部署版保持214611。
下一步完成NoAnim通知/状态认证、真实0x98上下文转换及唯一纯表现调用；P0/P1未完整完成，
P2/P3未实施，动画仍未修复，实机暂停。

### 最新接续记录（2026-10-13）

本轮仅离线分析与回归，按用户新提供 AGENTS.md 的暂停要求没有游戏启动/输入/QA 指令。
证明 Show facade 为 9 参数，原生上下文构造尺寸0x98，普通受击走 SBShowAnimKey；
工厂登记阶段根据 bEnable / bCheckHitLevel 过滤完整受击动作。完整轻击根资源的
ChangeAttachTo 会进入反射 ProcessEvent，未认证回调副作用之前不能整条重放。
原版 NoAnim 资源实际保留两个叠加受击动作，其 HitLevel=false 活动子图结构检查通过，
可作为最小候选，但目标状态、通知和 MC 命中上下文未完成；不等同完整硬直或动画修复。

证据：`docs/hit-reaction-20261012/native-show-contract.md` 与扩展的 show-runtime-evidence.txt。
新增离线 `sb-mod/tests/hit-reaction-evidence.py`：7 锚点、196 字节变异、3 版本/缺证据负例、
4 资源负例通过；明确输出 productionInvocationCertified=false。P0 仍未完成，P1–P3
未实施，没有构建/部署游戏组件或新增实机验收。无需用户再次攻击，已有验收不重开。
采样工具本轮发生外部删改，保留未覆盖；当前版本不能完整重现旧快照的全部字段。

### 最新接续记录（2026-10-12 23:47）

用户回复“已攻击”，读取当前剑星 PID51456、基址0x140000000，对应本机认证exe。用户自行操作；本轮没有输入/启动、没有游戏代码写入/注入/函数调用。独立采样工具已运行，**采样成功不等于动画修复通过**。

采样证据 `docs/hit-reaction-20261012/eve-hit-resource-graph.json`：335421对象、990个SBShowData；110个相关表现资源、154条Eve Normal/LightAttack结果、207个已加载轻击受击动画。最终快照拒绝读取0。最初`loaded-resources-2248.json`因姓名池RVA加法错误产生334817拒绝，**该零资源结果无效**；已改为从认证LEA的下一指令+位移计算真实`0x6FFEC40`，没有放宽对象身份/地址门禁。后续15个资源拒绝来自合法的空key，当前保留显式null记录。只读工具布局本次得到实际数据支持，仍不能跨版本泛用。

原版结果行 `P_Eve_Sword_Normal_LightAttack1_1_Hit1` / ID100100101：

| 配置 | 当前读取值 | 含义与限制 |
| --- | --- | --- |
| targetEffect | SkillDamage_Eve | 全命中流程包含原版伤害，不能接在当前扣血之后再次执行 |
| hitLevelTargetEffect | HitStun，Time=0.3 | 原生硬直是另一个效果，不应与纯动作播放混淆 |
| targetShow | Hit/Result_Hit_Stand_Sword_LightAttack | 已确认普通Eve轻击结果的配置资源；快照没有记录本次调用的准确时刻/技能对象 |
| downShow / groggyShow | 对应LightAttack_NoAnim | 原版特殊状态有意不用普通动作，不能强制覆盖 |

普通轻击表现 UObject 包路径 `/Game/Art/Show/Hit/Result_Hit_Stand_Sword_LightAttack`。其中4个SBShowAnimKey负责常规/附加方向动作，优先级读到100；其中2个原生key需要HitLevel，2个附加key不需要。还包含5个SBShowPlayShowKey（怪物相对HitReact_LightAtk、通用FX、Emil/Emil1st/Dororong特例）与3个SBShowChangeAttachTo（rightwing Off、leftwing Off/On）。这些依赖及条件仍需核查执行代码，不能据“未见EffectKey”就宣称完整表现绝无副作用。

**重要修正**：仅按SDK中的USBShowHitReactionKey追踪会漏掉普通受击。当前唯一加载的该key属于`P_Eve_Sword_Normal_Guard1_JustParry`，普通动作实际走SBShowAnimKey；当前怪物HitReact_LightAtk/StrongAtk只含粒子。SDK枚举HitReaction=2与现场该key容器索引4不同，不能把SDK枚举直接当当前版本调度索引。

207条已加载轻击受击AnimSequence的通知采样仅包含SBAnimNotify_FootStep、SBAnimNotify_CharSESound、AnimNotify_PlaySound或无通知，未见伤害通知；这仅覆盖本次已加载范围。完整字段、外层包路径、通知类及嵌套资源指针已保留JSON。`show-runtime-evidence.txt`补入完整facade与原版目标调用端：Windows x64下观察到前三参数为facade/ControlGUID/FString，第4参数使用xmm3槽，之后5个栈参数包含HitLevel、上下文及3个布尔；第4参数具体语义、完整上下文和执行副作用尚需最终核对，不能先猜签名调用。

**下一步**：依据原版结果和资源图继续核对SBShowAnimKey条件、子事件/通知及调用参数，选择经认证的纯表现分支，再进入P1/P2。P0完成标准仍未全部满足；P1–P3未实施，生产DLL/JAR/Lua保持不变，伤害/盾牌/贴图等验收保留。暂不需要用户再次攻击。

### 最新接续记录（2026-10-12 22:45）

继续离线追踪，证据为 `docs/hit-reaction-20261012/show-runtime-evidence.txt`（`tools/inspect_hit_reaction.py --show-runtime`）和 `resource-index-evidence.txt`（`tools/inspect_hit_resources.py`）。没有启动或操作游戏，没有修改生产组件。

- 原生 HitReaction key 执行方法为 `0x2451530–0x2452060`，包含多个 unwind 片段。先校验表现绑定的 ASBCharacter 与 key 类型，读取表现上下文 `+0x70` 的向量及角色 `+0x1DE8` 的 FString ResourcePath，再计算相对方向权重。
- key 的四方向路径 `+0x58/+0x68/+0x78/+0x88`、additive `+0x98`、起止时间/播放速率/淡入淡出 `+0x9C–0xAC`、循环次数、优先级等进入四个原生动画调用：`0x20B19E0`、`0x20B25C0`、`0x20B31D0`、`0x20B2AC0`。这只是静态数据流证据；尚未完整证明这些调用的 ABI、动画通知副作用或原生霸体条件，不能直接接入。
- 当前 `pakchunk0-WindowsNoEditor.utoc` 的未加密 v3 目录可只读解析，9700 目录、221592 文件；筛出 42 个相关资源，包括 `Local/Data/SkillResultTable.uasset` 和多个普通怪的 `Show/HitReact_LightAtk.uasset` / `HitReact_StrongAtk.uasset`。压缩方法为 Oodle，本轮没有解码资源内容。不能把目录存在作为资源仅含动画的证据，更不能以名称猜全部敌人的资源映射。
- 新增单次外部只读工具 `tools/probe_hit_resources.py`，用于用户运行普通区域时枚举已加载 SBShowData 的 key 类与 HitReaction 参数。只申请 VM_READ/查询权限，不调用游戏函数、不写进程内存、不注入、不发送输入、不自动启动或反复采样。核对选定 PID 对应本机 exe、SHA、基址/姓名池指令；有界读取对象索引、稀疏 map 分配位和 key 列表，复核身份/序列，读失败拒绝。工具语法及本机姓名池指令门禁通过；真实对象数组、key map 布局和采样尚未在进程中验证，不能将此工具称为实机通过。

离线信息仍不足以决定普通 EVE 命中的实际表现资源及完整上下文。下一步需用户自行普通启动剑星，以 Eve 在大沙漠轻击一个普通敌人后停留；助手只读加载资源，并继续对照命中路径。MC 不必启动，此前“暂不需要操作”适用于离线定位阶段；当前已准备好只读工具，需要这一原版对照。**P0 仍未完成，P1–P3 未实施，受击动画尚未修复；已验收 HP/盾牌/移动等不重新设计。**

### 最新接续记录（2026-10-12 22:30）

用户确认上一轮修复均已正常，转入本文的新受击动画任务；原有已验收问题不再重新排查。用户自行操作游戏、助手只读日志/修复的范围保持。核查目前没有剑星/MC 进程。

P0 本轮可复查证据：`docs/hit-reaction-20261012/offline-entry-evidence.txt`，由 `tools/inspect_hit_reaction.py` 对本机 exe 离线生成。SHA-256 `573AAFF1C9455F85EA6036EF6F6CCB0774DE4164722A296276FBBFC7FA87545C`、timestamp `6A6A3B74`、SizeOfImage `15981000`。工具不加载游戏、不调用游戏代码、不读取进程内存。下述地址全部为当前版本 RVA，**是分析线索，不是允许调用的 ABI**。

- 游戏诊断字符串 `FSBSkillHitResult::Execute` 指向 `0x1B171A0` 开始的原生命中 job；函数有多个 unwind 片段，不能只分析第一个区间就认定没有副作用。
- 该 job 在 `0x1B17472` 调用 `0x1C4FB20` 客户端技能命中处理。此处理先查询结果数据，验证施法者/目标状态；按目标的状态、原生条件与结果表选择表现资源。普通目标资源来自结果表 `+0x58` 的 FString，与 SDK 的 `ResultTargetCommonShowPath` 对应；倒地、Groggy 等另有分支。
- 目标表现请求在 `0x1C50396` / `0x1C50465` 调用 `0x22857E0`。此 facade 验证 ControlGUID、查找真实 UObject、解析表现路径，并进入表现调度。它是通用表现入口，尚未验证路径、上下文和 key 的所有副作用，不能直接当成安全的纯受击入口。
- 同一技能命中处理在 `0x1C50115` 调用 `0x1A7F880`；后者在 `0x1A7F9B9` / `0x1A7FA49` 进入 `0x1C77590` 效果执行链。因此不能将整个技能命中处理作为纯动画函数接到现有 HP 扣减之后。是否再次产生 HP/状态变化仍需追踪效果数据与执行分支；目前没有排除重复结算。
- 原生 `USBShowHitReactionKey` 的类型检查已定位到 `0x2451407`、`0x245157A`、`0x2452091`。这是已有表现实例的内部处理分支，尚未证明可独立构造/调用，不能跳入其中或直接写动画状态。

**当前未满足 P0 完成标准**：还缺经过验证的普通 EVE 命中结果资源、表现上下文/原生优先级条件，以及该资源仅执行表现、不重新触发 HP/状态结算的证明。下一步继续追踪 `0x22857E0` 的调度与受击 key 实例创建、结果资源和效果执行分支；若需动态对照，只读取用户自行操作普通区域时的证据，不发送输入。P1–P3 暂未实施，未改协议，也未替换已验收 DLL/JAR/Lua；不宣称动画已修复。

### 1.1 用户报告

MC 人物攻击剑星敌人时，虽然伤害能够传递并减少敌人血量，但敌人不像被 EVE 原生攻击时一样播放相应的受击动作，例如身体后仰、方向性受击、硬直及恢复动作。

这里的“受击动画”指**剑星原生敌人的受击表现**，不是 MC 生物的红闪或 hurtTime；这两类问题不能混为一谈。

### 1.2 修复目标

- MC 的有效攻击能够触发与剑星自身战斗规则相符的原生受击反馈。
- 受击方向、轻重攻击和敌人本身的霸体、无敌、死亡状态保持一致。
- 伤害只结算一次，不重复扣除 HP，不重复触发致死或僵直。
- 原有 MC→剑星比例伤害、敌人 GUID/类型校验、模式隔离、MC 盾牌与地图/世界门禁保持不变。
- 优先让**普通敌人的 MC 近战受击动画**工作，再扩展到箭矢、TNT 等情形；不承诺所有敌人共用一组动画。

## 2. 已核查的现有源码链路

```text
MC 玩家攻击剑星敌人代理 ActorProxy
  └─ mc-mod/.../world/ActorProxy.java
       ├─ hurt()：过滤来源、验证当前代理并调用原版 hurt
       └─ actuallyHurt() → DamageBridge.forward()
           └─ mc-mod/.../world/DamageBridge.java
                ├─ 计算/转发 MC 原版最终伤害
                ├─ EV_HIT_ACTOR：uid、伤害、weapon、flags 等
                └─ 写入共享内存事件环
                     └─ sb-mod/lua/main.lua
                          ├─ apply_events()：身份/世界/序号/遮挡检查
                          ├─ damage_actor()：转换伤害级别
                          └─ enemy_stats() → sc_enemy_damage_lua
                               └─ sb-mod/src/shm_bridge.cpp
                                    └─ sb-mod/src/native_combat.h
                                         └─ native_combat::actor()
                                              └─ invoke_apply()
                                                   └─ 剑星原生数值/HP 入口
```

已知现有伤害链路可以减少剑星原生敌人 HP。**尚未找到这条链路内明确执行原生 Hit Reaction（受击动作）的接口**；现有“原生入口”不等于已证明拥有完整受击表现。是否存在游戏状态对动画的额外限制，需进一步定位和验证。

### 2.1 关键文件与发现

| 文件 | 当前行为 | 待修复关注点 |
| --- | --- | --- |
| `mc-mod/src/main/java/dev/stellarcraft/world/ActorProxy.java` | 原版 `LivingEntity.hurt` 结算后由 `actuallyHurt` 转发；代理不可见，剑星绘制真实敌人 | 不要在 MC 代理播放假动画；应该触发原生敌人动作 |
| `mc-mod/src/main/java/dev/stellarcraft/world/DamageBridge.java` | 区分空手、剑、斧、工具、箭等，输出 MC 伤害事件 | `b/c` 击退方向目前填 0，`d` 默认 0.3，缺乏完整、可信的命中信息 |
| `protocol/stellarcraft_protocol.h` | `McEvent` 32 字节、包含 `uid/a/b/c/d/flags/weapon`，定义了 `HitFlags` 和 `HitWeapon` | `flags` 目前实际承载 `nextEventId()` 序号；不能直接当位掩码读 |
| `sb-mod/lua/main.lua` | `apply_events()` 调用 `damage_actor()`；`weapon` 目前主要用于箭矢遮挡分支 | 伤害成功后没有明确的原生动画回调 |
| `sb-mod/src/shm_bridge.cpp` | `sc_enemy_damage_lua` 依据当前 UE 角色身份调用原生伤害接口 | 可在此增加经验证的、与游戏线程绑定的受击表现桥接 |
| `sb-mod/src/native_combat.h` | 校验程序版本、游戏线程、GUID/类型和实际 HP；`invoke_apply()` 调用原生数值接口 | 大量攻击上下文参数为 `nullptr` / `0`，不能认定会发起技能命中动画 |
| `sb-mod/src/damage_applier.cpp` | 旧代码骨架，`resolve_actor/apply_damage/drain_events` 均未实现 | **不是当前实际伤害执行路径**；不要直接在此补一套并行伤害逻辑 |
| `sb-mod/src/SDK/SB.hpp` | 存在 `USBShowHitReactionKey` 及技能命中相关签名 | 可帮助追踪实际原生受击入口，但声明不意味着能直接安全调用 |
| `sb-mod/tests/native-combat-routing.lua` | 验证世界身份、伤害序号去重、TNT 遮挡等 | 后续补受击事件触发次数和拒绝条件回归 |

### 2.2 原生入口当前已确认的信息

`sb-mod/src/native_combat.h` 里的调用逻辑：

```cpp
reinterpret_cast<Apply>(base + 0x1BA8720)(
    owner, 1, nullptr, -amount, cause, nullptr, 0, 0, 0, 0, 0
);
```

它的现有离线校验和实机日志支持“原生 HP 减少”；但传入参数并不包括已验证的完整攻击者对象、命中骨骼、冲击方向、技能命中等级与原生表现标记。不能凭此证明原生硬直、方向动画、受击特效等已经执行。

`DamageBridge.forward()` 已发送武器种类，但 `apply_events()` 并未将其转换为原生受击表现语义；此外没有可依赖的命中方向记录。这是当前最直接的功能缺口。

### 2.3 SDK 中可供追踪的线索（非可直接调用的解决方案）

- `USBShowHitReactionKey`：`FrontAnimResourcePath`、`BackAnimResourcePath`、`LeftAnimResourcePath`、`RightAnimResourcePath`，以及淡入淡出、播放速率、优先级等配置。
- `ESBShowKey::ShowKey_HitReaction = 2`：原生表现系统中的受击反应类型。
- `ClientBroadcast_SkillHitResult`：签名包含命中结果、目标 GUID、命中点、法线、骨骼、方向、HitStop 和 ShowKeyTag。
- `ClientBroadcast_ActorHitResult`：签名包含命中地点、法线、骨骼和方向。
- `CH_M_NA_42_AnimBP.hpp` 中出现 `FSBAnimNode_HitBones` 等动画图节点。

**必须先验证以上字段/调用所参与的真实游戏流程。不要直接调用不明 ABI 的 `ClientBroadcast_*` 或自行设置动画状态。** 它们可能要求有效技能、效果、网络/原生上下文，盲目调用可能崩溃或者意外重复伤害。

## 3. 计划方案（按阶段）

### P0 — 确认原生命中与受击表现的真正关联

1. 在当前安装版本的 SDK / 反汇编 / 现有只读日志中，追踪 EVE 正常击中普通敌人时，原生代码从命中判定到动画/表现调用的路径。
2. 区分 **HP 数值更新**、**原生受击/硬直判定**、**动画表现播放** 与 **死亡表现** 四个步骤。
3. 验证是否存在*只触发受击表现而不重复修改 HP*的安全入口，确认 ABI、调用线程、目标身份、参数含义和触发后的副作用。
4. 不能确认安全入口时，先记录证据与阻塞项，不猜偏移、不放宽原生身份校验、不在生产链路尝试任意调用。

**P0 完成标准**：用源码/符号/离线分析明确至少一个可复核的受击入口及其前置条件；明确其是否会再次修改血量。

### P1 — 补齐有身份保护的命中上下文

1. 复用已有的 `ActorProxy` 和唯一伤害事件；关联 `uid`、GUID、类型、进程、当前世界和事件序号。
2. 从合法 MC 攻击上下文计算攻击来源和方向：近战使用受控玩家/被击对象位置；箭矢使用验证后的轨迹方向；TNT 使用爆炸中心。不要从“附近最近敌人”推测攻击者。
3. 仅在实际需要时扩展事件字段，使用明确的协议版本和 Java/C++/Lua 一致性检查；若当前 32 字节记录不足，避免无版本保护地改写结构。
4. **保留事件序号语义**：当前 `flags` 写入的是 `nextEventId()`，不能同时按照 `kHitCritical` 等位标记解析。应考虑独立编号/标记字段，或采用版本化旁路事件。
5. 依据原始来源与原生转换规则，给受击表现提供可靠的 `weapon`、攻击方向/命中点、是否真实扣血等信息。

**P1 完成标准**：回归可确认每个有效 MC 命中拥有唯一、有效、不过期的目标与方向信息；旧世界/旧进程/重放事件一律拒绝。

### P2 — 连接原生受击表现，但保证 HP 只结算一次

优先方案 A：

- 保留已验证的 `native_combat::actor()` 伤害路径。
- 在 `result.applied == true` 且敌人仍有效、未死亡、并满足原生状态条件时，调用 P0 验证过的**纯表现入口**。
- 只有原生系统允许播放/衔接时才播放。避免强制覆盖敌人当前攻击、霸体、无敌、Groggy、倒地及死亡动作。

备选方案 B（仅当没有纯表现入口时）：

- 如果完整的原生技能命中/效果管线同时负责 HP 与受击表现，则统一改走该管线。
- 必须取消与之重复的 HP 扣减，重新校准 MC→原生比例换算、有效命中去重和死亡流程。
- 对所有 ABI、原生对象/调用线程及版本偏移继续实施白名单/身份校验。

**禁止**：为了“有动作”而每次强制播放任意 Anim Montage、直接写动画内存或对同一击执行两次原生伤害。

### P3 — 按攻击类型扩展效果与原生优先级

| MC 攻击 | 第一阶段目标 | 后续可拓展 |
| --- | --- | --- |
| 空手 | 轻量原生受击 | 与盾牌/霸体交互 |
| 剑 | 普通斩击受击，方向正确 | 连续打击过渡、暴击 |
| 斧 | 原生允许时较重的反馈 | 硬直等级/击退 |
| 镐、铲、其他工具 | 与实际伤害匹配的常规受击 | 钝击与特殊打击分类 |
| 弓箭 | 被击中时受击方向正常 | 命中骨骼、命中特效 |
| TNT | 对受影响目标呈现原生冲击 | 空中/倒地/爆炸特效 |

具体受击等级、能否打断与动画资源**以剑星原生规则为准**，不能硬编码“所有攻击必定打断”。

## 4. 不可破坏的现有约束

- 保留当前原生 exe 版本、ABI、GameThread、GUID、类型、UObject 精确映射、目标可受伤状态、HP 合理性及地图/进程校验。
- 不对 Eve、友善 NPC、后台 MC 生物或未登记目标发送原生受击/伤害事件。
- 不修改已验收的 MC→原生伤害换算结果；受击动画不得二次扣血。
- 不改变正在工作的 MC 模式保护、MC 原版盾牌结算、TNT 原生场景保护和 F4 模式切换行为。
- 动画事件失败应安全返回并记录可诊断原因，不得造成游戏线程异常、对象失效访问或停止正常 HP 结算。
- 当前已有工作区未提交修改；实施前先检查 `git diff`，仅修改当前任务涉及的位置，不覆盖其他进行中的修复。
- 按 `AGENTS.md` 当前要求：**用户自行操作游戏；助手不代启动、不发送游戏输入**。

## 5. 验证策略

### 5.1 离线测试与构建

1. 为 `sb-mod/tests/native-combat-routing.lua` 增加有效/无效命中、重复事件、旧世界 UID、来源歧义、事件时效及“HP 未减少不得触发受击”检查。
2. 为原生受击调用增加与现有 `native-combat-gate.cpp` 同等严格的版本、指针、线程、GUID/类型和拒绝分支验证；离线测试中游戏调用必须替换为 stub。
3. 验证一次有效事件最多造成一次伤害和一次允许的受击表现请求；连续不同序号事件允许按原生状态机处理。
4. 校验 Java/C++/Lua 协议布局、构建 JAR/DLL、Lua 语法、世界门禁和基本攻击回归。
5. 将“构建通过”“离线模拟通过”和“实机通过”分别记录，不能以编译通过代替动画实际可见。

### 5.2 实机验收（仅用户自行操作普通区域）

| 场景 | 验收标准 |
| --- | --- |
| 空手/剑轻击普通敌人 | 敌人出现符合原生状态的可见受击动作 |
| 连续剑攻击 | 受击动作可衔接、不永久僵直、不重复扣血 |
| 斧/工具 | 至少有正常受击反馈，不误触发倒地/处决 |
| 弓箭 | 箭矢命中对应一次伤害和合理方向的动作 |
| TNT | 仅合法受影响敌人出现与爆炸匹配的受击反馈 |
| 目标无敌/霸体/死亡 | 不非法中断动作、不覆盖死亡、不重复伤害 |
| 多敌人同时受击 | 各目标反应独立，GUID/世界身份无串扰 |
| F4 往返 MC/EVE | 不残留错误的动作状态，EVE 原版受击正常 |
| 切换地图/复活/失效代理 | 不触发旧世界事件，不发生悬空对象访问 |
| 性能/稳定性 | 无异常动画频繁重启、明显掉帧或崩溃 |

**实机状态**：目前未进行本问题的实机修复测试；当前文档仅为待实施方案。

## 6. 推荐实施顺序和交付清单

1. **P0 原生流程定位**：拿到真实受击动作入口及副作用证据。
2. **P1 命中上下文**：稳定关联方向、目标、唯一事件 ID，且不破坏 v4 协议。
3. **P2 最小验证修复**：先只让 MC 剑/空手攻击普通敌人触发原生受击。
4. **P3 扩展**：加入工具、弓箭、TNT 的合理方向和冲击表现。
5. **离线与实机验收**：更新回归测试与状态文档，保留回滚备份和构建哈希。

建议未来实施时形成以下记录：

- 修改前后的相关文件 diff；
- 受击入口的版本/ABI/线程/副作用证明；
- 协议兼容性与事件去重测试输出；
- DLL/JAR/Lua 相关产物的 SHA-256、备份位置；
- 用户实机可见动作、扣血次数、拒绝原因和稳定性证据。

## 7. 当前结论与风险

**结论**：当前 MC→剑星攻击已经连接原生数值/HP 管线，但没有经过证实的完整原生 Hit Reaction 调用。最有价值的修复点在剑星侧 Lua → DLL → 原生表现接口，以及必要的命中上下文补齐，而不是让 MC 不可见代理自行播放动画。

**主要风险**：原生受击机制可能依赖技能/效果状态；直接伪造网络广播或动画播放可能破坏 AI、重复伤害或导致崩溃。先验证原生入口，再小步接入，优于直接调用推测 API。

**状态记录**：本文档为针对用户新报告的静态审查与实施计划，未对现有工作区源码做任何改动。
