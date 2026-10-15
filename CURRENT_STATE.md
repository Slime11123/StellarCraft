最新 2026-10-15（MC生物AI方案第五轮：P1 NoAI来源准备项已实施/安装，完整AI仍未开启）：NativeEntities在现有setNoAi(true)前保存首次登记的原始NoAI=true/false标签；已有native但缺来源的旧实体记unknown，不根据强制保存值猜测。重载不覆盖已有来源标记，损坏/冲突不自动升级；不改旧运动/攻击/导航、协议或原生门禁。完整P1未完成，P2–P4未启用。

新增NativeMobAiState和实际NBT内存重载回归；JAR构建/remap、成品保存顺序、既有命中/跳劈/世界事件/地图清理回归通过，均为离线。安装前无剑星/Java、QA0字节且核对旧七组件后，只备份替换JAR：B80FF7867A8E49B1A74BEA46B56368620731557488E28ECD3746C4958C222379，源/候选/部署一致；其余六组件不变。最新部署清单/回退见docs/mob-ai-p0-20261015/ai-state/manifest.json、before-install/；实施说明AI_STATE_IMPLEMENTATION.md。实际存档保存/重入未实机验收，未读写真实存档。

P0另新增bindings模式10段/14锚点，明确CreateActor普通请求可能走事件出生，内部完整工厂含额外参数；完成回执只给请求ID+bool而非新GUID。同一effect ID可循环结算多个目标，stat入口还有通用效果/递归分支，不能当作唯一攻击序号或独立物理命中证明。证据：docs/mob-ai-p0-20261015/bindings/BINDING_FINDINGS.md及verification.json；四模式28/17/8/14锚点、错误版本/冲突模式拒绝通过。

P0原生代理整体门槛仍未通过，SB主动攻击MC继续阻止生产接入；该门槛不妨碍MC端独立准备项。下一步优先推进MC导航快照/物理反馈及NoAI后续命令约束，再同步移除旧手写AI并恢复原版AI；不得单独解除NoAI。原生安全配置/线程寿命/真实命中→UUID继续认证。游戏测试暂停保持：未启动、输入、写QA或进入Boss；当前无剑星/Java，QA0字节。以下为历史记录。

最新 2026-10-15（MC生物AI方案P0第四轮：默认效果消费者/姿态/组件）：扩展离线取证工具的components模式，完整解析Effect4227/CharacterStance252/TargetFilter1866行，加上原三表均精确EOF；解压四个实际Dummy/墙面蓝图，仅记录名称引用，不把NoCollision名称或CollisionGroup名称当作有效属性/受击认证。

Passive_Immortal明确配置Infinite+Immortal27；exe HP路径实际查询27，将最低HP限制为1，另一伤害分支也检查27。UsePlayerAIPositioning104被定位流程读取，正值时改用管理器指定角色；不能扩大解释为所有移动都跟随Eve。Nikke姿态还禁止lock-on/auto-lock-on；普通Dummy姿态含BeachExplosion命令引用。Off_HUD与Off_Hud仅大小写差异，已修正取证别名解析。目标表仅4个Eve SwordAura模式显式开启bValidEngineHandle，不能将前轮条件外推至全部技能。

证据：docs/mob-ai-p0-20261015/components/COMPONENT_FINDINGS.md、component-candidate-rows.json、table-evidence.json、lifecycle-evidence.json、verification.json。新增5段/8个CALL及数据锚点、旧资格17/生命周期28锚点、六表EOF、别名解析、错误exe/解码器及冲突模式拒绝、旧默认三表候选保持通过；均为离线核查。七份部署哈希保持shield-scale基线，QA0字节，无剑星/Java；未改生产/构建/部署/游戏资源，未启动或操作游戏。

P0整体仍未通过，P1–P4未启用。下一步离线核对创建完整参数、蓝图组件属性/真实碰撞数据关联和effect受害者→MC UUID唯一事件映射；实际线程/双表寿命/感知命中仅在用户明确恢复后验证，游戏测试暂停保持，不写新QA指令。以下为历史记录。

最新 2026-10-15（MC生物AI方案P0第三轮：原生目标资格与真实角色配置表）：新增资格模式14段有界反汇编/17锚点，确认方向性Tribe关系、引擎ControlGUID及隐藏Actor过滤；LOS桥在对象缺失时也返回0，不能用它单独认证视线。owner保留依赖关联角色/配置/世界状态，不能简化为HP或alive。

新增 tools/inspect_native_mob_tables.py，固定本机UTOC/SDK版本、核查外置解码器哈希后离线解压解析Character644/Tribe19/Appearance211行，均精确到EOF；项目只保存候选行和元数据。N_Dummy的AttackTribe_All不代表Monster反向敌对；M_DummyA/B/C是Monster友军且有AI；M_Dummy_Nikke具备被Monster敌对的阵营线索，但引用墙面蓝图并带Passive_Immortal/玩家AI定位等效果，不能直接认证为移动代理。TrainingRoom只解压，数组格式未匹配，未采用配置猜测。

证据：docs/mob-ai-p0-20261015/qualification/QUALIFICATION_FINDINGS.md、proxy-candidate-rows.json、table-evidence.json、verification.json。资格17及原生命周期28锚点、四工具语法、错误exe/解码器拒绝、资源索引旧默认42项逐字节保持通过；均为离线核查，非实机验收。七份部署哈希保持shield-scale基线，QA0字节，无剑星/Java进程；未修改生产、构建或部署，未启动/操作游戏，暂停保持。

P0整体仍未通过，P1–P4未启用。下一步离线核对候选蓝图/碰撞命中组件、默认效果及创建完整参数；动态线程/双表寿命/实际命中→MC UUID仅在用户明确恢复后验证，不安排游戏测试。以下为历史记录。

最新 2026-10-15（MC生物AI方案P0继续：原生创建/双表清理静态链已追出，生产认证仍未通过）：新增 tools/inspect_native_mob_lifecycle.py 和 docs/mob-ai-p0-20261015/lifecycle/。真实CreateActor RPC→请求执行→server owner初始化→原生角色配置/构造→GUID/ControlGUID/角色数组登记，与客户端创建重放的共用登记链已核对。移除是先加入+1D8队列，再进入原版Despawn/Destroy及角色索引清理；服务端owner另在+170表扫描清理，不能以删除Mesh/清角色表代替双表释放。

补上RunAITask回调对象的静态构造、登记和类型ID索引证据；复核服务端stat调用与客户端重放的两个不同返回点及effect受害/来源owner关联。现有Eve hook仍严格限定watched/GUID/type100/线程/时效，不借此放开Mob伤害。36段有界反汇编、28个CALL/指针关联、工具语法和错误exe版本拒绝通过，均为离线取证；详见 lifecycle/LIFECYCLE_FINDINGS.md、lifecycle-evidence.json、verification.json。

MC兼容战斗代理的合法配置/完整参数、两套登记实际寿命/线程，以及原生感知和真正命中→MC UUID的动态关联仍缺，P0整体未通过，P1–P4未启用；不能宣称已恢复原版AI或双向交战。当前无剑星/Java进程，七份部署哈希保持shield-scale-20261015基线、QA0字节；未修改生产逻辑、构建、部署、启动游戏或发送输入，游戏测试暂停保持。下一步核对兼容角色资格/资源、owner保留谓词和命中条件，动态门槛仅在用户明确恢复后验证；不安排游戏测试。以下为历史记录。

最新 2026-10-15（按MC生物AI/双向仇恨方案执行P0；完整门槛未通过）：已新增 tools/inspect_mob_ai.py，只读本地1.20.1映射JAR、SDK和exe，导出15类实际字节码与原生调用链。确认NoAI挡住原版Goal/Brain/Navigation，noPhysics绕碰撞；现有CollisionField无生产写入、拾取高度场/25Hz位移反馈不足以导航，已明确PathNavigationRegion、WalkNodeEvaluator独立碰撞及Sensing视线读口和导航快照契约。旧登记实体没有保存原始NoAI，不能统一强行开启。

原生DetectTarget已追到RunAITask队列、ControlGUID内部登记查表、服务端操作2及角色候选筛选；当前MC外观Mesh不在该角色表。SBCreateCharacter实际native exec只解析参数后返回，不能用作代理工厂；锁定RPC也不能替代敌人仇恨。合法原生战斗代理构造/注销、回调线程/寿命及实际命中受害者关联仍未认证，P0整体未通过，P1–P4未启用。不能宣称已恢复AI/绕障/双向交战。

证据及复查：docs/mob-ai-p0-20261015/P0_FINDINGS.md、inputs.json、verification.json；专项计划顶部已更新。工具语法、错误exe版本拒绝和静态锚点核对通过；均为离线证据，无新JAR/DLL构建或部署。核对当前七份部署哈希保持shield-scale-20261015基线，QA0字节；没有启动游戏、输入或写QA，游戏测试继续暂停。下一步先离线追目标筛选的候选资格、合法角色构造/移除和命中桥，补齐P0后再按计划实施，不放宽ABI/身份/线程门禁。以下为历史记录。

最新 2026-10-15（新增 MC 连续攻击→剑星原生硬直／Groggy 计划）：静态审查已找到原版 HitStun、ActorState_Groggy=7、ImmuneGroggy=16、Groggy_Start/Loop/End 与 GroggyEndTime 的 SDK 线索；当前只能确认存在这些机制，尚不能证明普通攻击若干次触发的确切原版条件或安全原生调用 ABI。现有 MC 单次 HP/盾伤、Light/Strong Show 不产生独立累计硬直；强 Show 实机尚未通过，Timed HitStun 调用尚未认证。分阶段 P0 原版连击触发源→P1 有效命中→P2 原生硬直/恢复→P3 兼容隔离→P4 离线回归计划详见 docs/PLAN_MC_COMBO_NATIVE_STAGGER_2026-10-15.md。本次只写文档，未改生产战斗、未构建/安装/启动游戏/QA；现有逐步削盾候选保持安装状态、游戏测试暂停保持。下一步按 P0 离线取证，禁止凭固定击数假硬直或强制改怪物状态；以下为历史记录。

最新 2026-10-15（用户执行逐步削盾方案：已构建、离线通过并安装）：盾伤改为 min(currentShield, 实时MaxShield*mcDamage/tierBudget)，HP换算不变；替代此前“沿用HP系数”的原方案。保持本击破盾不溢出HP、下一击重取盾、无盾槽/回盾/免伤及全部ABI/身份/线程/读回/Show门禁，无新增倍率配置。

用户真实样本44000HP/2160盾/伤害8/Elite预算100已先复现旧版测试失败，新版请求172.8盾伤；按静态确认的原生delta取整桩，首击剩1988，固定伤害无回盾时13击破盾且HP不变，下一击仍扣3520HP。10倍HP不改变盾伤、实时MaxShield变更、极小伤害取整不转扣HP及既有矩阵通过；3427战斗/Show和995玩家门禁字节变异、Lua路由去重/来源、实际DLL/Lua协议回归与构建通过，均为离线证据。

核对无剑星/Java、QA0字节和旧七组件哈希后，只备份安装DLL：626E3F44A667969362B88D1E9D360D3A2C645A2F73440DDE90E2F1EA13704199，源/候选/部署一致；Lua/JAR等其余六组件不变。证据/回退：docs/shield-scale-20261015/IMPLEMENTATION.md、manifest.json、before-install/。未启动游戏/输入/写QA，测试暂停保持；新版数值及普通敌人/Boss破盾表现未实机验收，下一步等待用户按授权反馈。以下为历史记录。

最新 2026-10-15（用户反馈一击破盾，现有日志确认换算数值原因）：只读当前日志，GUID3639/type205039001：Elite budget100、MaxHP44000、MaxShield2160、MC伤害8，原方案同一k计算盾伤3520，超过整条盾2160。event8盾2160→0而HP44000不变，event9无盾HP44000→40480；该样本未见溢出HP，不能将其当作重复扣盾。现有公式符合原方案，但满盾相对HP过小导致一击破盾。建议后续按实时MaxShield而非MaxHP换算盾伤（本例约172.8/击、原生整数截断），此建议尚未实施，等待用户确定数值方向；当前部署不变。证据：docs/shield-first-20261015/ONE_HIT_SHIELD_DIAGNOSIS.md、user-hit-combat-only.txt。没有游戏操作/QA/构建/部署，助手测试暂停保持。以下为历史记录。

最新 2026-10-15（MC→原生敌人盾优先：已修复、离线通过并安装，实机未验收）：按 docs/PLAN_MC_NATIVE_SHIELD_FIRST_DAMAGE_2026-10-14.md 实施。每击认证并读取当前HP/Shield池；有盾仅一次原生stat=7扣盾，本击破盾不溢出HP，下一击无盾才扣HP；无盾槽正常扣血，回盾重新优先盾。异常/身份/线程/版本失败拒绝，不回退HP；调用后重查身份、读回盾与HP，盾路由要求HP不变。新增Shield字段/路由日志与仅掉盾受击上下文，保留全部Show/强击限制、玩家保护、伤害去重和v6布局，JAR未改。此前查询解包、Boss入伤加成与扫描profile保留。

真实C++结算路径（只注入原生函数桩）、破盾/下一击/回盾/多目标/无盾/非法池/读取故障/身份变化/免伤/SEH、Shield上下文与去重通过；战斗/Show3427及玩家995字节变异拒绝、Lua战斗/1000次World查询/生命周期/F4/移动/台阶/语法、实际DLL/Lua新结果字段和v6事件/地图回归、DLL构建通过。stat=7 setter静态确认+134/+138与零盾分支，实际原生破盾事件/HUD/普通敌人/Boss换相及动画仍未验收，不能把桩当实机。

确认无剑星/Java、QA0字节并核对旧七组件后，备份安装配套DLL+main.lua，源/候选/部署哈希一致：DLL 4193F7273D97D979128906AE96101CF95ADDC06D1649BDD4C12F114ED15A523F；main.lua 3EAF419F1FB60DECE99ACBA844BA190D79A78D2F8691AD24FEC7DA202786FA71。其余五组件不变。交付/证据/回退：docs/shield-first-20261015/IMPLEMENTATION.md、manifest.json、before-install/。未启动游戏/输入/写QA指令，游戏测试暂停保持；下一步仅在用户明确恢复后按授权验证普通有盾怪，Boss实测仍未授权。此前伤害恢复/不再崩溃的用户验收及重击/Timed HitStun未验收状态保留。以下为历史记录。

最新 2026-10-14（用户确认伤害恢复；Boss 对 MC 玩家伤害小幅上调并安装）：用户反馈“可以了”，上一轮敌人登记/攻击伤害恢复按用户实测确认，不重复定位。本次只调整原生 Boss → MC 玩家：Elite（精英首领）、Alpha、Elder 三档经当前世界 Actor 地址/GUID/type/epoch 和 0.5 秒来源时效复核后，转发伤害乘 1.25，单次继续封顶 20 HP。Minion/Warrior/未知类不变；过期、歧义及身份不符来源无加成。仍由 MC 原有护甲/盾牌处理，ABI、线程和身份门禁未变。

离线回归覆盖三档 Boss +25%、普通/未知类不变、上限、过期/错误类型/歧义来源无加成；既有唯一伤害/命中方向、1000 次地图查询、生命周期及移动回归、Lua 语法通过。确认无剑星/Java、QA0字节并核对旧七组件哈希后，仅备份安装 main.lua，源/候选/部署 SHA256 83B8756C3E877AC53A9F7A890EA6313CB3BF4ECD13302E38E58A777009A73BD8，其余六组件不变。证据及回退：docs/boss-incoming-damage-20261014/manifest.json、regression.txt、installed-before-main.lua。

Boss 数值调整尚未实机验收；下次正常启动生效，后续以用户实际受击反馈判断强度。未启动游戏、发送输入或写 QA 指令，助手游戏测试暂停保持。此前用户确认的不再崩溃及伤害恢复保留。以下为历史记录。
最新 2026-10-14（伤害回归：当前地图查询结果未解包，已修正安装，实机待确认）：用户报告无法伤害原生敌人。日志中候选50–102但nearby/published始终0；查询本身无异常。匹配d3d1004源码确认ObjectProperty数组元素为RemoteUnrealParam，须先:get()得到UObject。上轮遗漏元素解包，参数对象被Actor:IsValid的pcall过滤；测试替身直接返回Actor也漏测。本轮已在查询内部立即解包/验证，登记与命中复核均使用正确Actor，不保存参数包装，不恢复全局扫描/旧Actor缓存，不放宽任何伤害门禁。

测试现返回真实形式的参数包装且禁止直接调用Actor方法；旧版在查询及完整战斗路由回归均失败，修正版通过1000次零全局扫描、对象变化/失效/异常、敌人发布和命中复核、唯一HP/跳劈样式/箭矢/TNT/来源/旧目标拒绝。移动/F4、台阶、生命周期及语法通过。证据/备份：docs/actor-query-unpack-20261014/DIAGNOSIS.md、regression.txt、manifest.json、installed-before-main.lua。

确认无剑星/Java、QA0字节后仅安装main.lua，源/候选/部署SHA256 D330A12373CE5C7EF63880F13B21E081ADC5CE6F7891212675F437EFCE6088E6；其余六组件与map-clear-20261014一致。切图清空和EVE扫描优化保持，协议v6不变。未启动游戏、输入或写QA指令。用户已确认的不再崩溃保留；实际伤害恢复和扫描优化后的EVE流畅度仍待新实机日志，不以离线通过代替扣血。

下一步由用户普通操作后只读candidates/nearby/published及唯一Combat HP，确认登记与伤害恢复，再核查EVE镜像扫描尖峰。助手游戏操作暂停仍生效，不重新索取已验收结论或放开ABI/身份/线程/时效限制。以下为历史记录。

最新 2026-10-14（用户确认不再崩溃；EVE卡顿扫描修正已安装待实测）：用户明确当前复测没有崩溃，保留为已获用户确认的当前普通操作结果，不主动重开原崩溃定位。本轮只处理切回EVE的移动卡顿。最新日志显示相机正常恢复，EVE transform平均约0.02–0.04ms，但mirror每200ms扫描的峰值55–77ms；空/少角色地图同样有明显扫描耗时。

已将collect_character_actors的全局FindAllOf回退改为引擎GameplayStatics.GetAllActorsOfClass当前World查询；本机SDK签名和匹配d3d1004的Lua数组出参回填源码已核对，保留5Hz发布/EVE联动和现有身份/伤害/epoch门禁，不缓存旧Actor。查询失败清空目标并限频报错，不恢复全局扫描。Profile补充nativeWorld阶段，供区分剩余实体/粒子耗时。1000次零全局扫描、空/改变/失败查询、失败目标清零与原战斗/移动/F4/台阶/生命周期回归和语法通过；旧版在同一测试失败。新反射调用和EVE移动改善尚未实机验证，不把离线当实际帧率。

核查无剑星/Java、QA0字节后，仅备份安装main.lua，SHA256 3E3B1D28EBF467CACF2068190C98A43A058529340DAB42E519629620E1339A32；其余六组件与map-clear-20261014一致。证据/备份：docs/eve-stutter-20261014/DIAGNOSIS.md、manifest.json、installed-before-main.lua。未启动游戏、输入或写QA指令。切图清空和上一轮包装身份修复保持。

下一步由用户普通操作后只读新Profile，确认mirror尖峰和EVE观感改善、无world character query unavailable；若仍卡按nativeWorld/publish新证据继续。用户已确认的“不再崩溃”不重复定位；历史旧条目中的“崩溃待验收”以本次用户反馈为准。助手游戏操作暂停仍有效。以下为历史记录。

最新 2026-10-14（低帧率/刷日志回归已修正安装，帧率待实机）：用户报告帧数极低。只读当前日志：重力校准/RootMotion/移动接管各1176次、飞行开启345次，主要是每帧重复初始化而非持续tick异常。原因是上轮每帧新建Controller/Pawn包装，而接管逻辑用Lua包装不等判断切换；另有每Tick全局查控制器开销。已改为epoch+原生PC/Pawn地址判身份，胶囊/地面缓存也按地址；ACTIVE使用当前epoch上下文与Controller.Pawn，仍核对Pawn/World身份，仅REBINDING全局查找，并将Controller EndPlay纳入失效屏障。切图清空功能和全部生命周期/伤害门禁保留，未关闭EVE联动。

新1000不同包装回归在旧源码失败、修正后保持单次接管和最初RootMotion保存值；1000次ACTIVE准备不增加全局扫描，Pawn/Controller销毁重绑、旧Tick、运动/飞行/F4、台阶及战斗回归/语法通过。只读日志时游戏已关闭，核查无剑星/Java后备份并仅安装main.lua，源/候选/部署SHA256 CBB283F7F30573FC083E615EFCA7D3058A4F59148C91724A43CF634682CB54E7；其余六组件与map-clear-20261014清单一致。证据/回退：docs/perf-wrapper-20261014/DIAGNOSIS.md、manifest.json、installed-before-main.lua。未启动游戏、输入或写QA指令。

下一步由用户普通启动/操作后只读新日志确认移动初始化不再逐帧重复，再根据用户帧率和新Profile判断实际改善；修正后FPS尚未测量，不宣称全部性能/原两类崩溃通过。游戏操作暂停仍生效，切图清空仍待实机。以下为历史记录。

最新 2026-10-14（用户变更：切图清空旧 MC 生物/方块，已部署未实机）：已将上一候选“新地图恢复旧账本”改为按稳定地图名称变化清空。SbState原保留0x3C发布mapId，独立于带epoch的levelId；v6布局/大小与C导出ABI不变，Lua私有第14参数配套更新。首次观察只关联；真正换地图时账本方块/偏移清空并持久保存，集成服务端无掉落移除登记方块、丢弃登记旧实体；实体持久代次标签和渲染快照阻止旧生物由卸载区块重新出现。返回旧地图不恢复；F4、同地图重绑/复活保留。只清登记MC内容，不删除后台MC地形/生物或剑星场景/Eve/NPC。

DLL/JAR构建/remap、实际账本空NBT持久化/往返清空/无方块仍清实体代次、旧实体标签/快照拒绝、新实体接受、mapId稳定且与运行token独立、实际DLL/Lua第14参数与失效/省略清零、生命周期/运动及17项DLL运动姿态和既有Java命中/跳劈回归通过。仅离线；实际服务端删除/保存重入与两类崩溃消失均未验收。未改动真实MC存档、启动游戏、发送输入或写QA指令。

确认无剑星/Java、QA0字节后，备份旧七组件并成套安装；源/候选/部署哈希一致。证据/清单/回退：docs/map-clear-20261014/IMPLEMENTATION.md、manifest.json、before-install/。当前DLL E30E74B7A7DB85660113EEA010C0A8A7C4F9526C23655942587EDEF7B35ABC47；main.lua 2406903D4FB4C3A40AF4E0732FB928E01B2740265712A77A13FF57F416A03824；JAR A767AFBA7371B27BE3B775538A6AC7422F4247268B308AD1F45459B417A884FF。addon未改，须使用配套DLL/Lua/JAR。

下一步仅在用户明确恢复游戏测试后，由用户普通操作验证换地图清空、回原地图不恢复、F4保留及新地图重新放置/生成。游戏测试暂停仍生效；两类崩溃与重受击原未验收状态保留。下方“重建/重同步旧地图内容”和旧部署哈希为历史，已被本次用户要求替代。

最新 2026-10-14（EVE 切图/切回 EVE 崩溃：生命周期候选已实施并部署，实机未验收）：按 docs/REPAIR_EVE_MAP_TRAVEL_CRASH_2026-10-13.md 实施 World phase/epoch、LoadMap 预卸载及 InitGameState/EndPlay/ClientRestart/QuitGame 门禁。匹配 UE4SS d3d1004 源码证实 LoadMap 仅分发首个模组；已避开 BPModLoader 占用的 PostHook，以当前无冲突的 PreHook 停机，实际回调覆盖/顺序仍待实机核查。卸载只遗忘旧 UObject，不 IsValid/Destroy；旧 Tick、F4、命中及跨图事件拒绝。目标/来源只缓存标量并从新注册表复核，绕开 UEHelpers 旧控制器缓存。保持 v6 布局/现有伤害与受击门禁，levelId 包含 epoch，世界事件及 SCFEED3 反馈验证标识，MC 方块按新原生世界重同步。

DLL/JAR 构建/remap、Lua 语法和生命周期毒化旧对象/同地址重入/旧闭包/旧事件回归、现有战斗/运动/台阶/身体/粒子/方块回归通过；557/2989 字节变异、实际 Java 发布及实际 DLL/Lua 事件/地图和17项运动姿态检查通过。两份原始转储/日志已备份；exe 与 UE4SS 的 timestamp/SizeOfImage/RSDS 均匹配。A 类对象索引读取及 B 类 RDX+39 近空写分别保留，B 类具体参数来源未确认，不能宣称两类崩溃已修复。

确认无剑星/Java进程、QA0字节后，备份旧六组件并安装 DLL+五份Lua+JAR，七份源/候选/部署哈希一致。清单/备份/证据：docs/map-travel-20261014/manifest.json、before-install/。当前 DLL 52BA1646BAA2DC1FF9E3252796FD56BAB4A285A0A85297B47AC40DF369BEDEEE；main.lua D05D227A045BC53059CA851BA035C75AB2AD47CE169CA7EB6B892CE221F3615D；JAR 98125B0C4BD4B4AD50C73F39F064A850EAA452A7872AA94E2A246993FF57DC8F。其余哈希在manifest。布局仍v6，但本次组件须成套更换，addon未改。没有启动游戏、发送输入或写QA指令。

下一步：等待用户明确恢复游戏测试后，由用户普通操作，多次验证原失败切图/MC→EVE/退出、新地图相机控制及方块重建，分别核查两类签名和生命周期通知；此前轻击验收保留，重受击/Timed HitStun仍未通过，本轮未扩展该任务。当前暂停要求优先，不沿用下方历史“请启动/采样”的邀请。以下为历史记录。

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

最新 2026-10-13（用户确认轻击通过；跳劈重受击v6已实施/安装，实机待验收）：用户明确轻击已有攻击动画，按通过保留，不重查。按PLAN_MC_JUMP_CRITICAL_NATIVE_HEAVY_HIT_2026-10-13.md实施P0/P1/P2/P3：NativeJumpAttack保存不可变宿主运动快照（UUID、PID、MC世界、地图、传送epoch、时间、真实高度/速度/触地），须证明先离地上升再真实下降，样本<=250ms，原版攻击冷却>0.9且排除疾跑、流体、骑乘、攀爬、失明、滑翔/飞行等。Player.attack HEAD捕获重置前冷却；只拦截一次主目标Entity.hurt调用，在try/finally内限定伤害上下文，横扫/箭矢/TNT不继承重标记，不改fallDistance或MC最终伤害/暴击倍率。实际1.20.1 Player.attack字节码和remap注入点构建检查通过。

协议同步v6，布局仍32字节；weapon低8位类型、bit8跳劈视觉，flags仍唯一serial；元数据和伤害批量逐位匹配，未知位/重标记箭矢及TNT只拒绝视觉。一次有效命中只选择LIGHT或HEAVY。只读用户自行启动的PID52216：110Show/207动画/128条Eve剑技能、155原生实体，拒绝0；StrongAttack2_1 SkillID1001007 HitLevel=1，读取当前target+1AC抗打断门槛。MC映射到普通强击等级1、原生装备加成0，不借用Eve装备属性或强制true。目标当前门槛<=1且原有身份/线程/状态/标签条件全部满足时，六类CH_M_NA_09/11/46/47/48/49选择原版完整StrongAttack的两个CustomAnim方向键；源快照/通知/根子图与IoStore缺失子资源、原生2459E0A..2459E15 null拒绝已审计，完整Strong根无ChangeAttachTo/伤害键，不调用SkillDamage或HitStun。40/45变体路径未认证重分支，28/52原有缺证据，重请求保守降级到可允许的轻反馈；未认证者没有被扩大白名单。

JAR/DLL/addon构建、Java宿主跳跃/身份/时效/冷却/异常清理、真实Java打包字段/序号/旧版本拒绝、协议对齐、2989字节门禁变异、LIGHT/HEAVY九参数单请求stub、5重资源/通知负例、Lua唯一HP及样式配对/未知位拒绝、实际v6 DLL/Lua映射/事件与17运动/姿态回归通过。用户正常退出后，确认两游戏关闭，备份并统一安装四组件，源/部署SHA一致；QA0字节，没有助手游戏输入。候选/旧四组件/完整哈希：docs/jump-heavy-20261013/candidate-v6/manifest.json、before-install/。只读样本strong-levels-52216.json，资源检查heavy-evidence-check.json。当前DLL 5A1DEEB78082F79DB905104464A3BBAC0F8E2F9FE7C2BC1FADF0DD53CA83512F；Lua 56C6477D892AF2809AEEDE2BE8A05F8ED51BC9FD6CDA2F1B27F5102B36EA9657；JAR 5C0CC56ECCC1B81BD4BDA89DEE08EB9B1A210B15AFFA5B8997D379D5444126A5；addon BA9DF73CA0EEA576D2601B880488CC3E440A8CAF72E1659182792ACA63EC738A。

**轻击用户验收通过；重受击已实现/安装，P4实机未通过，showRequested/Heavy=true仅表示请求，不等于肉眼已播放。** 下一步用户自行同一普通敌人对比地面轻击和满冷却、不疾跑、下降阶段跳劈，读取JumpAttack及HitContext的jumpHeavy/selectedHeavy、回退原因和一次HP。此前22:29一次启动F4崩溃未证明根因；回退尝试在游戏进程检查处拒绝，未替换任何文件，后续用户确认轻击正常；不将其当重击接入证据或宣称已修复。游戏仍由用户操作，助手只读日志/修复；以下为历史记录。
最新 2026-10-13 22:48（EVE 切图/切回 EVE 崩溃专项只读审查）：读取最新 22:38:18 UE4SS.dll+0x71B7DF 读访问冲突与 22:29:42 SB-Win64-Shipping.exe+0x29B6A27 写 0x39 两类转储；14:38:06 UTC MC→EVE 后仍执行敌人扫描，但最新日志不能证明已开始切图。审查当前 Lua 的世界 Actor 缓存、8ms 异步 Tick、材质/相机/实体生命周期，分阶段修复计划已写 docs/REPAIR_EVE_MAP_TRAVEL_CRASH_2026-10-13.md。根因未最终确认，尚未修复、构建、部署、运行游戏或实机验收；不覆盖协议 v5 / 受击动画等未提交变更。以下为历史记录。
最新 2026-10-13（新增 MC 跳劈→剑星重受击方案）：用户确认当前 MC 普通轻击已有剑星原生受击动画，提出下降跳劈触发明显不同的原生重受击。已静态审查 ActorProxy→DamageBridge→事件环→Lua→native_hit_show 全链路，发现 server/client fallDistance 强制清零、HitFlags 枚举与 flags 实际 serial 语义冲突，以及 StrongAttack_NoAnim 叠加动画仍复用 Light 资源。详细分阶段方案已写 `docs/PLAN_MC_JUMP_CRITICAL_NATIVE_HEAVY_HIT_2026-10-13.md`，包含宿主下落分类、独立事件强度、StrongAttack 完整动画/HitLevel 原生安全认证及测试/回滚。**本轮只写方案，未改生产代码、未构建/安装/启动游戏或做实机测试**；下一步 Codex 可按 P0→P1→P2 离线实施，用户授权后才进行游戏测试。以下为历史记录。
最新 2026-10-13（用户复测无效果：地图编号漏发布已修复/安装，仍待实机）：用户报告没有受击效果。14:21–14:22实机日志确认原有HP正常扣减，但存活命中均在prepare_hit_context拒绝：host not in MC combat state，ready=false/showRequested=false；死亡命中按预期拒绝。源代码证明SbState.levelId此前没有任何玩家发布写入，因此新增非零地图门禁始终不满足，尚未到原生Show入口，不能归因于动画资源或优先级。已补Lua native_level_id：对有效当前UWorld完整名称计算稳定32位哈希，缺失/失效世界返回0；Lua私有第13参数传入，DLL在同一玩家seqlock内发布levelId，退出游戏状态清零。保留C导出sc_write_player原ABI（内部write_player_frame接收地图字段），不改协议v5布局，不删除非零地图/状态校验。拒绝日志区分host map identity unavailable。

DLL构建通过；真实Lua发布参数回归验证有效/稳定地图、地图变更及空名称/失效世界；实际DLL/Lua验证地图ID的两次发布、失效/旧调用清零、偶数seqlock和F4/UI位保留；17运动/姿态、Lua移动与原有伤害/唯一上下文回归通过。核查两游戏已关闭后备份并安装配套DLL/Lua，源/部署哈希一致；JAR/addon保持上一轮v5，QA 0字节，没有游戏输入。候选、manifest、旧二组件、仅战斗日志在 docs/hit-reaction-20261013-level-fix-v5/。当前DLL A93650C529FE70A89EBA2B8EC000E0569F1A53302E9E771A800AD7958B40C453；Lua A25DAF38A15E8FF92E21B08EDDD3BB4EAE72EE402AC6514D7DCE567A56B8A338。**只确认发布缺陷已修复并离线通过，动画可见性仍未通过；本次失败测试没有发出原生Show调用。** 下一步由用户正常启动两游戏，用剑非致死轻击普通敌人，读ready/showRequested/reason并观察动画；若进入后续拒绝，只根据新证据定位对应条件，已有HP/资源结论不重开。以下为历史记录。
最新 2026-10-13（普通敌人叠加受击接入并安装，实机待验收）：按用户要求，删除当前受击计划及plan.md中相关模式待办/反复范围提醒，当前只处理普通敌人。新增native_hit_target.h、native_hit_show.h及精确指令锚点：已验证的唯一HP扣减成功且目标存活后，复核GameThread、UID/原生实体/ControlGUID、原始500ms时效、八种已采样角色资源路径、实时UObject槽/删除标记、空ShowKeyTagMap、原生状态6/7/8与无敌状态。任意标签（包括零计数但仍存在的标签）、未认证路径或替代状态均拒绝表现，保留原有HP。通过原版1C49290构造0x98上下文，保留不存在的骨骼/骨骼局部位置/来源ControlGUID/标签默认值，仅写真实来源、目标位置、方向、目标ControlGUID；认证默认零向量和类型1的vtable入口。使用原版9参数facade 22857E0请求Hit/Result_Hit_Stand_Sword_LightAttack_NoAnim，HitLevel=false，只允许原生叠加表现并保留优先级，不重放伤害/HitStun/附件根资源。showRequested表示发起请求，不等于动画已经可见；异常锁止后续表现，不回滚或重复HP。

DLL构建、2926字节变异拒绝、0x98构造/9参数stub、原生状态/标签/路径/活对象/错误线程负例通过；补正快照校验的state通知字段与标签“存在即匹配”语义，8个负例通过。Lua伤害/上下文与移动回归、协议布局、实际Java环和DLL/Lua解码、17组运动/姿态通过。两游戏均关闭后备份并一起安装协议v5的DLL/Lua/JAR/addon，源/部署哈希一致，QA仍0字节；未启动游戏/发送输入。候选、四组件哈希和旧版本完整备份见 docs/hit-reaction-20261013-additive-v5/manifest.json、before-install/。**普通敌人叠加受击已实现/安装，但尚未实机验收，不能称动画已修复。** 下一步用户自行普通启动和测试剑击，读取HitContext的showRequested/reason与唯一HP变化，再验证箭矢/TNT/工具。当前CH_M_NA_28/52缺已认证叠加动画，带标签角色保守拒绝；完整硬直等级与打断未实现，不将其当作叠加效果的交付结果。以下为历史记录。
最新 2026-10-13（只读补样完成）：用户自行启动剑星PID52836，助手仅只读采样。noanim-character-tags-52836.json 有93个怪物角色/110表现/207动画，拒绝读取0，确认FName.Number显示序号限制修正有效；实际生成名称接近INT_MAX。93角色涉及10种资源目录，2个CH_M_NA_09角色带InvisibleHydra标签，未发现本候选排除的特殊NPC标签。新增hit-reaction-targets.py逐角色解析原版NoAnim启用/HitLevel=false/要求与排除标签、相对动画路径及通知；78个角色的叠加子图证据完整，15个排除（1个剧情对象空路径、CH_M_NA_28的10个及CH_M_NA_52的4个缺已加载叠加动画），不把未采到动画说成资源不存在。4个特殊标签、损坏快照、伤害通知共6个负例拒绝通过，证据ordinary-target-audit-52836.json。此为资源/通知证据，未证明角色当前可受击状态、骨骼上下文或生产调用安全，productionInvocationCertified=false。没有Show/游戏输入/部署，动画仍未修复；暂不需用户再次攻击。下一步用该范围完成原生上下文和运行时目标/状态门禁，再接唯一纯表现调用；CH_M_NA_28/52保持拒绝直到其动画得到认证，首领不测。以下为历史记录。
最新 2026-10-13（命中来源/时效 v5 候选与只读补样）：用户明确恢复普通区域只读补样，自行启动/操作剑星；助手不启动/退出、不发送输入、不写 QA 指令，首领不测。P1 已补齐原始逐击位置、低32位原生时钟及已确认箭矢命中轨迹，捕获后跨线程传值，不再用消费时的角色位置冒充原始来源。协议 Java/C++/Lua/ReShade 同步升为5，布局不变；类型9/10与原命中成批发布，空间不足只保留原HP事件，缺失/不匹配/超500ms上下文仅拒绝表现，不改变既有HP。箭矢方向来自实际确认轨迹，TNT保留原爆心/伤害。新JAR、DLL、输入addon构建及协议、真实Java事件环、真实DLL/Lua原始时间位/回绕/单次消费、17组移动/姿态、Lua路由、232字节门禁与时效/箭矢负例通过。四组件候选/哈希见 docs/hit-reaction-20261013-context-v5/manifest.json，必须一起安装；**尚未部署，没有任何Show调用，动画仍未修复/实机验收**，安装版仍为214611。

只读PID51772快照 noanim-tags-notifies-51772.json 得110表现/207动画，拒绝读取0。确认 NoAnim 两个叠加动作排除 AdditiveNoPlay/NoNormalAttShow/EmilShop_Flag_1st；Emil两子表现分别要求NoNormalAttShow、EmilShop_Flag_1st，Dororong要求Check_Dororong。离线证明原生243E1E0检查角色+3128及Show实例+140的标签并保留战斗条件；E6C4D0 map元素步长0x14。普通敌人的实际ResourcePath/标签仍待补样：两个带characters快照有331/92拒绝，不能当资源缺失证据；已发现诊断FName.Number的1000000限制不适用于接近INT_MAX的合法生成名称，移除此显示序号上限，地址/对象身份门禁保留，修正后实采尚待验证。PID51772已退出，旧PID采样被安全拒绝，不据此断言崩溃。下一步补读普通敌人后完成NoAnim目标条件/通知认证、原生0x98上下文转换及唯一纯表现调用；P0/P2仍未完成，完整HitLevel映射未完成。以下均为历史记录。
# 当前状态与待办

最新 2026-10-13 22:04：按用户新要求，已更新 `docs/PLAN_MC_MOB_AI_AND_SB_AGGRO_2026-10-13.md`：铁傀儡是中立阵营中的主动交战例外，在未受到攻击时也应通过原版 Goal 主动索敌、追击、攻击合法剑星敌对生物。补充目标白名单、ActorProxy 来源放行、单次原生扣血、原版动画/冷却/寻路与实机验收案例。仅修改方案和状态文档，尚未实现、构建或实机测试；不启动游戏、不影响其他开发项。

最新 2026-10-13（受击动画：命中上下文候选）：已开始修改游戏源码，P1 部分完成。新增 `native_hit_context.h`，在原有唯一 HP 结算成功且目标存活后准备 UID/原生 GUID/type/ControlGUID、MC PID/world、SB level、事件序号和归一化方向；复核扣血前后同一原生实体、当前可受伤状态、发布目标表、MC/SB seqlock 与控制/暂停/心跳条件。原生位置 accessor 的当前 exe 指令和 vtable 指向已离线认证，不调用该 accessor，只读实体位置。Lua 近战取当前受控角色原生位置，TNT 使用现有爆心换算；箭矢缺原始轨迹上下文则只保留原有 HP，不伪造方向。当前消费帧不等于 MC 原始命中时间，P1 仍缺逐击时效/来源及箭矢数据，不能称完整完成。**没有任何 Show/动画/效果调用，动画仍未修复，P0 未完成**；未改 v4 布局/MC JAR，没有部署。DLL 构建、232 战斗/位置锚点字节变异拒绝、命中上下文正反例、557 玩家/效果门禁变异、Lua 唯一近战/TNT上下文与箭矢排除/既有伤害路由通过。候选与哈希保存 `docs/hit-reaction-20261013-context-p1/manifest.json`；部署 DLL/Lua 仍为214611版本。下一步认证 NoAnim 的通知/目标状态并完成原生0x98上下文转换，再接纯表现调用。暂停游戏测试继续有效；不启动/输入/写 QA 指令，首领不测，已验收项不重开。

最新 2026-10-13（受击动画本轮接续）：继续离线 P0；用户新提供的 AGENTS.md 替换旧指引且含暂停要求，未启动游戏/发送输入/写 QA 指令，也未替换游戏组件。已确认表现 facade 原调用为 9 参数、0x98 原生上下文构造、真正普通动画分支 SBShowAnimKey，以及 bEnable/bCheckHitLevel 在工厂登记阶段过滤完整受击动作。完整轻击表现还有 ChangeAttachTo → 反射 ProcessEvent，不能据资源名认定无副作用。原版 NoAnim 资源实际有两个叠加受击动作；HitLevel=false 时活动子图结构只含动画/粒子/音效/震动，作为最小候选，不能等同完整轻击硬直已实现。证据 `docs/hit-reaction-20261012/native-show-contract.md`；新增 `sb-mod/tests/hit-reaction-evidence.py`：7 锚点、196 字节变异拒绝、3 版本/缺证据负例、4 资源负例通过；明确 productionInvocationCertified=false。P0 未完成，未新增生产调用/构建/部署，动画仍未修复。下一步核对候选动画通知与目标状态/方向上下文，再接唯一命中事件；完整硬直需原生 HitLevel 映射，不能强制 true。`tools/probe_hit_resources.py` 本轮期间被外部删改，保留未覆盖，当前版本不能完整重现旧 JSON 的全部字段。其他新增 AI/血条计划保留，既有验收不重开；暂不需要用户再次攻击，首领不测。

最新 2026-10-13 00:20：用户要求恢复剑星场景生成 MC 生物的原版 AI/寻路，和平/中立/敌对按 MC 规则与剑星敌人交战，且剑星普通敌人按原生索敌逻辑主动攻击 MC 生物。完成静态源码审查，方案写入 `docs/PLAN_MC_MOB_AI_AND_SB_AGGRO_2026-10-13.md`。确认 `NativeEntities` 禁用 Mob AI 并以手写直线追击代替；`ActorProxy.hurt` 尚不接纳登记 MC Mob 来源，剑星侧 MC 外观 Mesh 并非原生 AI 可攻击目标。P0 原生目标系统 ABI/合法目标认证和导航虚拟地形仍待完成；P1–P5 未实施、未构建/部署/实测。没有启动游戏或发送输入，首领不测，保留其他正在进行中的修改。

最新 2026-10-12 23:58：用户新增“MC 有效攻击剑星敌人后显示剑星原生头顶血条，原生脱战/死亡时隐藏”的实现方案任务。已审阅现有 MC 命中→原生 HP 链路和 SDK 原生血条/战斗状态候选，方案已写入 `docs/PLAN_NATIVE_ENEMY_HEALTHBAR_2026-10-12.md`。目前 P0 原版“头顶血条”的真实 UI/事件与脱战判据仍需认证，尚未实现、构建或实机测试；未启动游戏、未发送输入、不进入首领模式；原有受击动画调查和用户改动保留。

最新 2026-10-12 23:47：用户已自行用 Eve 攻击，助手仅只读采样 PID51456、基址0x140000000，未发送输入。修正诊断工具姓名池 RVA 加法错误（实际为 `0x2937894+0x46C73AC=0x6FFEC40`）；首份零资源快照无效，不能当资源缺失证据。当前 `eve-hit-resource-graph.json` 快照枚举335421对象/990个ShowData，110个相关表现资源、154条Eve Normal/LightAttack结果、207条已加载轻击受击动画，最终拒绝读取0；修正的是独立诊断工具，没有修改游戏组件。已证明原版轻击结果100100101的 targetEffect 为 SkillDamage_Eve，hitLevelTargetEffect 为 HitStun(Time0.3)，targetShow 为 Hit/Result_Hit_Stand_Sword_LightAttack；伤害、硬直、表现确为独立配置，不能重放整条命中处理。普通方向动作使用SBShowAnimKey；HitReact_LightAtk当前是粒子资源，唯一SBShowHitReactionKey属于Eve格挡资源，不能据SDK类名/资源名直接选动画。受击资源还包含子表现和wing附件事件；207动画采样只见脚步/角色音效/PlaySound通知，无伤害通知（仅本次加载范围，不等于全资源安全）。完整现场字段/条件见上述JSON；show facade完整参数读取及原调用端证据已加入show-runtime-evidence.txt。P0仍需确认这些条件如何适用于MC命中、完整表现上下文及子事件执行副作用，不能称为安全接入完成。尚未改/构建/安装受击功能，动画未修复；游戏仍由用户操作，首领不测。暂不需要用户再次攻击，继续离线核对入口/状态门禁。

最新 2026-10-12 22:45：继续 P0，仅离线读取当前 exe/IoStore。受击 key 执行方法已收窄到 RVA `0x2451530–0x2452060`：读取表现上下文方向和 ASBCharacter 的 `ResourcePath`（+0x1DE8 是 FString，不能当数组），根据原生 key 的四方向路径、additive、优先级和时间参数，进入 `0x20B19E0/0x20B25C0/0x20B31D0/0x20B2AC0` 动画调用；完整 ABI、动画通知副作用及霸体条件尚未证明。新增离线证据 `docs/hit-reaction-20261012/show-runtime-evidence.txt`。成功只读解析本机未加密的 v3 IoStore 目录，列出 42 个相关资源（含 SkillResultTable 和普通怪轻/重击受击资源），证据 `resource-index-evidence.txt`；内容使用 Oodle 压缩，目录存在不等于已解码/确认配置。准备 `tools/probe_hit_resources.py` 单次只读进程快照（只申请读/查询权限，核对 exe/PID、姓名池指令、有界 UObject/稀疏 map、对象身份复核）；语法及离线指令门禁通过，真实对象布局/资源采样仍未运行验证。未修改游戏组件、未构建/部署、未发游戏输入；两游戏未运行。下一步需要用户自行普通启动剑星、Eve 在大沙漠普通区域轻击一个敌人并停留，助手采样原版已加载资源作为对照，MC 不必启动，首领不测。动画仍未修复，P0 未完成，已验收功能不重开。

最新 2026-10-12 22:30：用户确认上一轮修复均已正常，转入 `docs/PLAN_MC_HIT_REACTION_2026-10-12.md`，当前只处理 MC 攻击剑星原生敌人缺少受击动作；已验收项不重查。用户自行操作，助手只读日志/修复，首领不测。P0 离线分析已定位 FSBSkillHitResult job、客户端技能命中处理、按目标状态选择 ResultTargetCommonShowPath 的表现请求、ControlGUID → UObject 的通用表现调度，以及 USBShowHitReactionKey 的内部类型检查分支。证据 `docs/hit-reaction-20261012/offline-entry-evidence.txt`，复查脚本 `tools/inspect_hit_reaction.py`。完整技能命中流程还进入效果执行链，未排除重复 HP/状态结算；通用表现入口与内部 key 分支尚未证明可独立安全调用。按计划 P0 第 4 条，未猜 ABI、未新增生产调用、未改协议或已验收游戏组件。P0 完成标准尚未达到，P1–P3 未实施，动画未修复/未实测。下一步继续证明表现调度的上下文、结果资源与 key 副作用，再接入唯一命中事件。当前核查两游戏未运行，没有游戏输入。下方历史未验收文字以用户本次确认及新任务范围为准。
最新 2026-10-12 21:46：用户自行操作本轮普通区域测试，助手只读日志/内存并修复；不发送输入，不启动/退出游戏，首领不测。21:33–21:38 共 66 个原生受击事件，MC 生存生命正常变化，EveHP 全程日志保持 3500，保护持续 active=true，直到用户 F4 释放；本轮保护租约修复已有受击实机证据。盾牌仍失败（event65 shield=true、direction=false，MC HP 11.473872→9.137537）。用户提供 `D:/1mc/错误报告-2026-10-12_21.39.11.zip`，21:38:44 MC Render thread 在 EntityRenderDispatcher.shouldRender 因 renderer=null 崩溃；服务端随后全维度保存。当前新增 ActorProxy 没有注册渲染器，已补专用不绘制的 ActorProxyRenderer 与启动注册，并检查打包/remap；这是代码缺陷修复，重启仍待验证，不将其等同剑星退出崩溃修复。

盾牌来源已沿当前 exe 证明：ApplyStatExecute 第八参数为效果对象 ID；generic logic lookup → effect+0x58 受击 owner / +0x60 来源 owner → owner+0x60 FSBActor。只读采样 sourceObject 3591/3606/3629 等均显示受击 GUID228、来源 GUID1645；rawKey 是效果键，不能匹配敌人 owner 的键或直接当 GUID。新增命中回调内只读有界哈希链、活对象 ID、效果键、受击实体、来源 owner/原生 GUID/类型/vtable 校验，保存原生来源位置；不在 TaskGraph 调用游戏函数。仅游戏线程消费受击队列，非游戏线程保留事件，不在错误线程丢失方向；Lua 再以当前地图登记 GUID 及精确 UE/原生关联复核，使用命中位置，拒绝歧义/过期身份。公开 v4 协议布局不变，DLL 与 Lua 同步部署。557 处玩家/效果门禁字节变异、来源错误/失效/类型/位置/哈希链负例、199 处战斗门禁、Lua 唯一伤害/遮挡/来源/世界回归与 JAR/DLL 构建通过；盾牌新方向未实机验证。

核查两游戏均关闭后备份安装 `docs/combat-integration-20261012-002957/candidate-214611/`（旧三组件、UE 日志、仅 MC 战斗标记日志、crash 文本及 manifest；未复制启动认证日志）。当前源/部署 SHA-256：DLL `8126F302BE4B464DFF7DE9F655D45179577C1EF353F671B4949D96C63640CB57`，main.lua `97B0E156511A4951C074FDF8C9AABD38A09EFA2A64359C302EAAF307BF7F968E`，JAR `81C53C62A783CEA66A4C23EEE00BDD237C1DAC7434583BE9E8D7EC18E2697333`。QA 0 字节。下一步由用户普通启动/操作，先看代理渲染崩溃是否复发及 HitSource attacker 非零、盾牌正反面与耐久，再继续弓箭/TNT及五项未验收场景。不可格挡/破盾分类、剑星退出崩溃仍未完成；既有验收不重开。下方记录为历史。
最新 2026-10-12 21:31：**用户选择自己操作游戏，助手只读取日志和修复；不再发送游戏输入或代用户启动/退出。** Karpathy Guidelines 已加入 AGENTS.md。本轮普通大沙漠登记和真实比例扣血已确认：GUID 1603（type 55046101）HP 26010→20808→15606→10404→5202→0，其他普通敌人也有扣血/死亡记录；不把未区分的武器来源、箭矢/TNT或盾牌都算通过。盾牌未通过：原生 rawSource=1000020/1000016 没有验证来源方向，举盾时仍扣血。另有新证据推翻此前本轮保护状态：MC 模式 EveHP 3500→2769，保护 active 反复切换；发现 native update 在拒绝非游戏线程之前就关闭 enabled。已修正为非游戏线程不能撤销有效租约、明确释放立即关闭、游戏线程身份失败仍拒绝；500 ms 租约和所有门禁保留。新增只读 sourceObject 诊断供下一轮追溯攻击来源；不将该对象 ID 或原始来源键当成敌人 GUID。199 处战斗/角色关联门禁变异、466 处玩家门禁变异、错误刷新不撤销/明确释放回归及 Lua 战斗路由通过。用户确认退出，MC 21:27:21 全维度保存；核查两游戏已关闭后备份安装至 `docs/combat-integration-20261012-002957/candidate-2129/`，包含实机 UE 日志和去认证的 MC 战斗片段。当前 DLL `C8D93D1EC02590450FE89833AEE4A08EFD3308F28FAD2315F80B0574E1AD9B99`，main.lua `37B206CED5E132BAE98362140814B4CA7FE5BC163C294A13D8E9BD372323ACC9`，源/部署一致；JAR 同 01:12。QA 文件为 0 字节。**新保护修复未实机复测；盾牌方向、弓箭命中、TNT与五项联合验收未完成。** 下一步用户普通启动并操作，读取 HitSource 的 object/rawKey 和保护/扣血日志，查明真实攻击来源再修复盾牌；首领不测，不重查其他已验收问题。退出崩溃仍未解决。下方运行状态均为历史。

最新 2026-10-12 21:19：按用户要求把 Karpathy Guidelines 核心原则和技能路径加入根目录 AGENTS.md（项目实际使用该文件）。Steam 登录已恢复，普通大沙漠加载成功。登记为 0 的根因已确认：单机 ASBCharacter.BroadcastSpawnInfo 的 GUID/类型均为 0。新增游戏自身 ControlGUID → UE 角色地址的精确关联，再使用原生服务端 GUID/类型/HP、ABI 和线程门禁；没有放开校验。DLL/Lua 已构建、备份安装，174 处字节变异拒绝与实际 Lua 战斗路由回归通过；21:19 共享目标表已发布 5 个普通敌人，真实尺寸、GUID/类型和 HP 可读取，这是登记通过证据，伤害/盾牌与五项联合验收仍待进行。备份 `docs/combat-integration-20261012-002957/candidate-2118/`；DLL `A9670AFD26D264ED8F690FAF931D001FE47271A9829BCD9C5DD1386C241973F4`，main.lua `377436ED4451B377880738D17A3549F64EB9DDB1A052B8109EB4F6CACE095A74`。21:04:38 正常退出再次出现错误报告，dump 已备份到 `candidate-2105/exit-210438.dmp`，未发送报告，退出崩溃未修复。当前剑星 PID 44540、大沙漠 Eve 营地；MC 正启动，QA 文件 0 字节。首领不测；既有验收不重开。下方登录阻塞为历史。

最新 2026-10-12 20:55（继续接续）：当前两游戏均未运行，Steam 停在登录窗口，实机暂被登录阻塞；已请用户自行登录，未操作认证界面。01:12 第二候选已安装，但未成功启动剑星并加载测试，不能沿用“重启测试中”作为已启动证据。新增实际 Lua 回归通过：MC 与原生遮挡率在二次 TNT 公式前合并（各 0.5 时伤害 9.75）、唯一来源键提供方向、三个来源键冲突持续拒绝方向。部署 main.lua/JAR 哈希仍为 01:12 记录，QA 已清空为 0 字节。首轮正常退出后出现错误报告，01:11:32 minidump 已备份至 `docs/combat-integration-20261012-002957/candidate-0112/exit-crash-0111/`；未发送报告，退出崩溃仍未修复。下一步登录完成后进入大沙漠普通区域，读取登记诊断，再做真实战斗及五项联合验收；首领不测，既有验收不重开。

最新 2026-10-12 01:12（战斗整合实施中）：完整方案已由用户粘贴，普通区域实测恢复，首领不测。v4 协议、服务端唯一代理/真实尺寸、伤害事件去重、原生比例伤害和无敌保护、统一登记 TNT 遮挡、验证来源位置后的原版盾牌结算已实施；构建和离线回归通过。细节见 `docs/PLAN_NATIVE_COMBAT_2026-10-12.md`。首轮大沙漠启动与 F4 正常；01:05 桥旁看到普通敌人但目标表为空，尚未攻击，需定位登记拒绝原因。MC 01:07:04 全维度保存、剑星 01:11 正常退出；核查无进程后备份安装限频登记诊断、原版实体攻击距离、遮挡公式及 hold-use 测试输入。新 main.lua SHA256 `4DA0A3B2F9BC0A86819ABB3E3DC0F1A904D96220BF8797D984BBE0CB3D1F818A`，JAR `5DC1C1CFC0C185FE4A1C78D3FF44BC6B85ACFE0260F384E20B92D27EEF45E3F3`，源/部署一致。QA 已清空，01:12 第二轮重启测试中；下一步定位登记、真实扣血/盾牌与五项联合验收，不能算实机通过。特殊不可格挡/破盾分类、三叉戟及火焰原生状态映射未完成。备份 `docs/combat-integration-20261012-002957/`。下方旧入口及暂停文字为历史，以本段为准。

最新 2026-10-12（伤害扩展接续）：用户要求先参考“实现 MC 伤害剑星怪物”计划实现武器/弓箭/TNT 对原生敌人的比例伤害及盾牌格挡，再与五项待测一起实测；此要求恢复普通区域测试，首领实测限制不变。引用对话读取只返回正文占位符，已询问用户粘贴最终计划及换算/格挡规则。独立源码审查确认：现有 Lua 使用 MaxHP × MC 原版伤害 / 类别预算（20/40/100/200/200）；原生伤害身份、ABI、线程校验保留；DamageBridge 入站伤害使用 mobAttack(null)，没有来源位置，原版盾牌方向判断无法正常工作；TerrainTargetMixin 显式重选实体未包含 ActorProxy，隐藏 MC 地形可能导致原生敌人无法选中；代理尺寸当前固定，原生命中精度待补；TNT 对原生敌人已有转发但尚未完成真实敌人验收。本次尚未修改伤害源码、构建、部署或启动游戏，进程核查未见剑星/MC。下一步获取方案正文后落实这些缺项，联合测试此前已部署五项；不重开已验收问题。

最新 2026-10-12 00:14：用户确认上轮**鞘翅显示问题已修复**，转入粘贴的五项最终计划：弓像素分离、箭矢显示/命中、创造背包 Shift、TNT 视觉、生物受击。代码已构建并备份安装；逐项实现、离线证据、部署哈希和限制见 `docs/PLAN_VISUAL_COMBAT_2026-10-12.md`。原版弓四态 PNG 覆盖、修饰键、外观叠加、整帧拓扑代次/丢帧、模型身份、箭矢连续撞墙、粒子序列/复用/64 上限、移动与 v3 协议 17 组检查通过；**五项实机均待验收**，剑星敌人代理命中精度和性能采样尚未验证。两游戏关闭、QA 文件 0 字节；本轮没有游戏输入/启动。电脑操作仍暂停，普通无 QA 启动尚未确认。伤害、贴图、击退及已通过的鞘翅显示不重复排查。以下时间段为历史。

最新 23:35：用户确认**鞘翅现在可以飞行**；具体俯冲、火箭、耐久和撞击伤害仍未逐项验收。用户报告第三人称翅膀偶尔成为静止长方体，本轮局部修复 `block_visuals.lua`：更换网格时清除旧局部基底的插值历史，立即提交新模型自身姿态；同模型动画保持插值。旧代码的模型切换回归失败，新代码通过，100 次装备模型切换及身体身份回归通过，Lua 语法通过。用户回复“已退出”，进程核查无两游戏，MC 23:34:43 全维度保存后完成备份安装；SHA-256 `52CECDEAC2C22D43B81ABCC9351B527D2630BFE19C8554C77D9250DA9C80382A`，源/部署一致。备份和候选在 `docs/elytra-visual-20261011-2334/`。**翅膀偶发问题修复实机待验收**，不能把已定位的模型切换缺陷直接视为全部原因。用户 Escape 停止电脑操作后未重新授权操作，本轮不启动游戏、不发送输入；QA 指令已清空，两游戏关闭。普通无 devQa 启动尚未确认。以下 23:23 及更早状态为历史。

最新 23:23：用户已实测确认**伤害、贴图、击退均通过**，不再重复排查。当前任务为用户粘贴的“移动、生物动画与鞘翅整合”九步方案，实施及证据见 `docs/PLAN_MOVEMENT_ELYTRA_2026-10-11.md`。协议已升级 v3，普通碰撞不再触发传送纠正，加入原版滑翔、烟花流程及真实触地反馈；动态姿态改共享内存并以进程/世界/UUID 门禁，生物步态和头部同步已改。JAR/DLL 构建、协议布局、16 组真实 DLL/Lua 数据检查、移动/飛行/滑翔/台阶/身体身份/原生世界模拟回归通过。**新功能实机尚未验收**。23:15 第一轮启动关联 version=3 成功，但旧 ReShade 输入组件拒绝新版本，已重建；两游戏退出后完成组件备份安装，23:22 重启准备继续普通区域测试。当前 QA 文件为空，临时进程带 devQa，收尾普通启动仍待完成。以下 19:25 及更早段落为历史。

最新 19:25：用户已完成伤害实测并确认没有问题，伤害规则按用户验收通过，不再重复排查。转为参考“代码审查与修复计划”修复人物/生物/手部贴图及登记 MC 怪物击退。尸壳导出 PNG 64x64 完整，六部件每个224–384 texel quads；大沙漠实际父材质为 Restroom/006_A_MI，存在随地图变化。已改为主动加载此前希雍验证的 Furniture_003/039_B_MI，并禁止纹理流式降级；成功怪物近战的 vanilla 击退速度传入受控 MC 本地移动，保留原生胶囊、隐藏生物隔离与伤害规则。JAR 构建/remap、Lua 语法/身体身份回归通过；已备份并安装，贴图/击退实机仍待验证。两个游戏此前已退出，全维度19:21:55保存。QA文件已清空。

更新：2026-10-11 19:25，北京时间。当前代码根目录为本目录。

## 状态

**用户已明确恢复普通区域实测，首领模式仍不测试。** 13:19 的退出/暂停是历史暂停点；本轮先核对 DLL/JAR/main.lua/native_world.lua/block_visuals.lua 源与部署 SHA-256 一致，确认游戏未运行、QA 文件为空。13:33 经 Steam 启动剑星，保持 Eve 模式，MC 尚未启动。

用户补充：当前地图是希雍，进入大沙漠时剑星在 Eve 模式直接崩溃。当前优先定位此切图阻塞，再进行大沙漠真实敌人 TNT 验收。已备份存档、原 UE4SS 日志及 12:05 minidump 至 `docs/desert-crash-20261011-133345/`。12:05 旧记录是 GameThread 的 UE4SS UObject 内部索引读取访问异常；尚未确认就是用户所报的大沙漠崩溃，不能据此宣称根因或修复。

13:36:45 用户通过与亚当交谈成功进入大沙漠；13:37 画面与 `E05_GreatDesert_P` 日志确认，MC/java/javaw 未运行，剑星稳定。成功切图日志：`docs/desert-crash-20261011-133345/UE4SS-desert-without-mc.log`。这只是“MC 未启动时切图成功”的证据；此前 MC 已启动或 MC 原生对象已激活时切图崩溃仍未定位/修复。下一步在当前大沙漠启动 MC，观察 Eve 模式稳定性，再继续真实敌人 TNT 实测。

当前优先任务已由用户补充为伤害路由：**MC 模式保护 Eve；MC 生存角色可受到剑星敌人和登记放置的 MC 怪物伤害；创造模式免伤；Eve 模式恢复原生伤害。** 首领模式范围不变。

13:39 大沙漠加载 MC 暴露 ActorProxy 继承方法未 remap，已将 ActorProxy 加入补丁编译和映射校验；13:41 实机成功加载敌人代理。13:45 接近普通机器人时原生 Eve 被击杀，复活后回到营地，触发上述新需求。

新伤害代码已构建/安装：本机 Eve 身份和游戏线程门禁下拦截原生 HP/护盾扣减，通过独立 seqlock 区把 HP 比例伤害交给 MC 生存玩家；登记 Monster 添加基础追逐/近战，须有新鲜原生碰撞和视线反馈，隐藏 MC 生物继续隔离。**伤害实机效果尚未验收**。最新原生玩家门禁离线通过 242 个字节变异拒绝检查；Lua 回归通过。

13:55 首次加载新通道触发 SharedMemory.readPlayerHurt 越界，已修正为单独映射预留 24 字节区域。17:41 重建 JAR，17:44 安装，哈希 B7FA305D206E5E9B614BC7DFBF1DBBD22724D99AEC50CA16AC9CC5A5BB7BC968；边界/半写帧测试通过。17:46 在大沙漠成功加载，17:47:20 全部 MC 维度保存，无该越界复发。

17:46 保护门禁未激活，未靠近敌人。18:03 Lua 通过原生地址比较确认 PlayerState.PlayerId=257、AcknowledgedPawn 与当前 Pawn 相同。18:06 只读原生采样确认 selected GUID=228、EventorActorGUID=228、type=100、HP=3500，native PlayerId=0；先前要求 native PlayerId=257 的证明不适用于此单人存档。改用经当前 exe Init/日志加载指令校验的本地世界玩家 GUID 与选中玩家/服务器实体 GUID 一致证明；线程、指针、类型、HP 和时效门禁保留。18:09 已安装 Eventor 身份版 DLL，待重启后验证保护激活及真实受击。营地坐标 MC=(-121.043,204.567,114.343)，无新 TNT/怪物放置；18:07:29 MC 全维度保存，当前在标题且进程带 devQa。QA 文件为空，普通无 QA 收尾未执行。

17:48:31 与 18:01:26 正常退出时出现 UE4SS 访问异常，备份分别在 exit-crash-174831/ 与 exit-crash-180126/。main.lua 现只在 MC 控制时读取玩家对象；18:08 正常退出未产生新崩溃，仅一次成功，不宣称退出或切图崩溃已修复。

备份/证据：`docs/desert-crash-20261011-133345/`、`docs/player-damage-20261011-135450/`（含越界错误筛选日志和替换前 DLL/Lua/JAR）。

## 已解决/已有实机证据

- 移动转向、跳跃、创造双空格飞行：用户确认正常。
- 台阶：五级金属阶约 0.7 秒通过；桥中央约 16 米 / 4 秒、约 1.3 米高差，往返无拉回循环。
- 单击放置一个方块；F4 切 Eve 再切 MC 后仍可放置；登记方块状态和原生原点持久化。
- 隐藏 MC 僵尸伤害测试生命 20→20，Shift 蹲姿与脚底稳定。后台 MC 碰撞/位置修正隔离。
- MC 猪和橡木船模型、像素贴图与原生阴影；超距实体停止隐藏位置模拟。
- 火把照亮剑星地面，萤石/火把创建原生灯光；珍珠命中检查和安全传送成功。
- TNT 点燃、引信、登记 MC 方块/实体破坏和伤害；13:13 像素爆炸粒子在画面中可见并正常消失，桥面完整、玩家生命 20。
- 性能在所测桥面约 123–154 FPS；粒子池版本爆炸时读数约 90、随后约 147。仅为该场景采样。

## 剩余事项（优先顺序）

1. **当前优先：五项视觉/交互修复实机验收**，见 `docs/PLAN_VISUAL_COMBAT_2026-10-12.md`。已备份部署、离线通过；玩家/生物移动、鞘翅各类特殊场景的历史未测项仍见原整合计划，不重开已验收伤害、贴图、击退或翅膀显示问题。
2. 大沙漠真实敌人 TNT：记录身份、HP 前后和死亡；仍未验收。MC 激活状态下切图崩溃根因未确认，不将 noMC 成功切图当作修复。
3. 针对实际出现的问题补测性能、其他实体及灯光熄灭/破坏路径。完整 MC 生物 AI、船浮力/水上航行未实现，不宣称已完成。
4. QA 收尾：普通启动不带 devQa 参数，确认无残留输入/TNT。当前文件已清空，但普通重新启动验证尚未执行。
5. GLFW 原生崩溃未证明修复。
6. 18:07 Eve 模式仍有红色线框，来源尚未确认；检查测试可视化残留。

## 按需查阅

- `plan.md`：完整当前现状、下一轮步骤、部署/存档/日志路径、构建入口与 SHA-256。
- `docs/REPAIR_2026-10-11_NATIVE_WORLD.md`：详细验收，顶部表格为最新状态。
- `docs/PLAN_2026-10-11_NATIVE_WORLD.md`：扩展与首领模式排查计划。
- 源码入口：`sb-mod/lua/main.lua`、`native_world.lua`、`block_visuals.lua`；`sb-mod/src/native_combat.h`；`mc-mod/src/main/java/dev/stellarcraft/`。

旧会话无需再次引用；遇到具体代码问题再查对应源码和证据。禁止恢复外部 overlay。
