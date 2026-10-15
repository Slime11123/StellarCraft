# MC 原版 AI / 剑星双向仇恨：P0 离线执行记录

日期：2026-10-15。依据 `docs/PLAN_MC_MOB_AI_AND_SB_AGGRO_2026-10-13.md`，用户本轮要求“按计划执行”，已进入执行阶段，旧文档末尾“本次只生成方案”不再限制本轮授权。游戏测试暂停仍有效。

同日第二轮接续见 [LIFECYCLE_FINDINGS.md](lifecycle/LIFECYCLE_FINDINGS.md)：已继续追到真实创建请求、角色与owner两套登记的清理路径、RunAITask回调静态注册及权威stat/重放调用关联。先前“未找到构造/移除链”的范围已缩小；MC兼容代理配置、完整ABI、线程和动态寿命/命中仍未认证，P0整体结论保持未通过。

## 当前结论

MC 原版 AI 的关闭点、实际 1.20.1 执行链和导航读取接口已经离线核对。剑星原生目标检测已追到内部队列、ControlGUID 查表、服务端目标操作和候选角色表；但**可供 MC 生物使用的原生角色合法创建/注销、最终命中目标关联、线程和寿命契约仍未认证，P0 整体未通过**。没有跳过门槛启用 P1–P4；本轮没有生产代码变更、JAR/DLL 构建或安装，也没有实机验收。

新增可重复运行的只读离线工具 `tools/inspect_mob_ai.py`，读取本地 MC 映射 JAR、SDK 和 exe 文件，不打开游戏进程、不加载或调用游戏代码。运行方式：

```powershell
python tools/inspect_mob_ai.py --output docs/mob-ai-p0-20261015
```

工具首先核对 exe SHA256；版本不同即拒绝。`inputs.json` 保存三个输入文件的 SHA256、分析区域哈希、两个 vtable 的静态槽位，以及两项始终为 false 的调用/生命周期认证状态。`native-references.json` 是字符串交叉引用和相邻数据值，**不是全部都属于函数注册**；`native-disassembly.txt` 的分析标签不是导出符号或可调用 ABI。

## MC 实际执行链

证据为本目录 15 份 `*.javap.txt`，来自 Fabric Loom 本地 Mojang 映射的 **1.20.1** 合并 JAR，SHA256 `D5D5503DC29E1A076F89F5E8D19C6E0C3866F3D87EF3900E9D244D28891B3339`。

1. `Entity.isEffectiveAi()` 是服务端条件；`Mob.isEffectiveAi()` 追加 `!isNoAi()`。`LivingEntity.aiStep()` 在 `isEffectiveAi()` 为真时调用 `serverAiStep()`。项目在 `NativeEntities.init` 的 ENTITY_LOAD 回调无条件 `mob.setNoAi(true)`，因此原版 AI 被这条路径关闭。
2. `Mob.serverAiStep()` 调用 Sensing、targetSelector / goalSelector、PathNavigation、`customServerAiStep()` 和 Move / Look / JumpControl。原版自身按 tick 奇偶使用 `tick` / `tickRunningGoals`；未来恢复时不能自行再调用一遍这些入口。
3. Brain 不是一条统一的 `Mob` 内部调用：本次核对 `Piglin.customServerAiStep()`，其中调用 `getBrain().tick(ServerLevel, this)` 及物种活动更新，之后进入父类处理。未来需恢复物种的正常 tick，不能只抽取 GoalSelector 后宣称 Brain 也恢复。
4. `Entity.move()` 的 `noPhysics` 分支直接 `setPos` 后返回，不经过普通碰撞裁剪。这不会代替 `NoAI` 关闭 Goal/Brain。项目 `EntityCollisionMixin` 对登记实体设置 `noPhysics=true`，无反馈时在 move HEAD 拦截；这只拦住位移，不能阻止已恢复的 Goal 在失效世界继续索敌/攻击。
5. 当前 `NativeEntities` 的 END_SERVER_TICK 仍有随机转头、固定 `.10/.045` 位移和 20 tick `doHurtTarget`；恢复 AI 时必须整体撤去这段行为控制，保留快照、碰撞反馈、箭矢/TNT和身份处理，不能让两套循环并行。
6. 现有 `registeredKnockback` 只由手写近战块显式调用。移除该块时必须在真实原版攻击的成功范围内保留一次击退转发；原版 AI tick 本身不会自动调用项目的转发函数。`Sensing.hasLineOfSight` 调用 MC 实体视线，不能以隐藏 MC 地形视线替代 SB LOS。
7. `IronGolem.registerGoals()` 已有原版 `MeleeAttackGoal`、报复 Goal 和原版主动目标 Goal。未来只补合法 ActorProxy 候选；挥臂/攻击数值继续由 `IronGolem.doHurtTarget` 执行，不能复制成固定近战伤害。`NearestAttackableTargetGoal`、`TargetGoal` 的开始、延续和停止条件亦已保存。

### NoAI 迁移必须保留来源

现代码只写 `setNoAi(true)`，没有保存登记前原值；已有实体的持久化 `NoAI=true` 无法仅凭该值区分“项目关闭”与“用户明确设置”。未来新登记可记录原始开关和版本化来源标记；**不能把所有旧实体一律改成 false，也不能推测其原始值**。旧实体的迁移规则尚未实施。

## 导航数据和局部接入点

| 已核对对象 | 当前事实 | 对后续实现的约束 |
| --- | --- | --- |
| `CollisionField` | 源码有 `putSection`，本次检索 `mc-mod/src/main/java` 没有生产调用方；ClientBridge 创建空容器并交给 Mixin | 不能因为类存在就宣称已有可用碰撞地形 |
| `TerrainRing` / `VirtualTerrain` | ring op=4 写入单个 `(gx,gz,topY)`，0.5 格高度场，只用于地面拾取；未知 op 直接跳过，无 AABB 消费 | 既没有墙体体积、头部空间、多层表面，也没有逐节点 TTL/epoch |
| Lua `sample_ground` | 围绕玩家 9×9、0.5m 格子、每次81次向下 Trace；移动时≥300ms、静止≥1500ms；另有75ms瞄准射线 | 不是围绕 Mob 的导航场，不能扩大此循环冒充完整导航采样 |
| Lua `native_world` | 最多16个发布实体；约25Hz实际位移扫掠、地面和近玩家LOS；外观 StaticMesh 无碰撞 | 反馈是已经发生的运动裁剪，不提供未来路径所需的空间可通行性 |
| `PathNavigation.createPath(Set,int,boolean,int,float)` | 在本地实际映射中构造 `PathNavigationRegion`，交给 `PathFinder.findPath` | 可局部替换“登记生物本次请求”的 region，保留 MC PathFinder；尚未编写/验证 Mixin |
| `PathNavigationRegion.getBlockState/getFluidState` | 读取缓存的 `ChunkAccess`；不是简单转发到 `Level.getBlockState` | 仅拦 Level 查询不足；优先请求作用域的只读 region，禁止全局改地形 |
| `WalkNodeEvaluator` | 节点类型、流体、地板高度和邻居查询使用 region；碰撞缓存的实际合成方法 `method_29305` 调用 `PathNavigationRegion.noCollision(mob, box)`，其碰撞块读取另经 `getChunkForCollisions` 获取缓存区块 | 自定义 region 还须提供碰撞区块视图；仅覆盖 `getBlockState/getFluidState` 仍不足，不能误认作直接调用 `mob.level().noCollision` |
| Sensing / Goal 延续 | 视线由原版实体查询，TargetGoal 会按失去视线和距离清理 | 需定向可信 LOS 数据；不能给全体实体无条件可见 |

导航快照的最小待实现契约：MC dimension、SB PID/levelId（含epoch）、稳定mapId、坐标原点代次、递增帧序号、采样时刻及TTL、分区覆盖边界、明确 unknown / blocked / traversable、支撑高度/法向、实体净空和相邻节点可通过性。多层地面必须保留各高度层；把没有采样的格子当空气会导致穿墙/坠落。实际登记 MC 方块按其碰撞形状和账本偏移合并，后台 MC 地形不能进入该视图。

服务端一次路径计算只能消费一个不可变快照。失效/缺失区域禁止生成可通行节点，运动端也应停止并重算；不能等物理反馈才知道已经穿入障碍。初批仅陆地牛/僵尸；水生、飞行和 Brain 专用行为不计入支持。

采样预算须跨帧、有活动 Mob 上限和共享分区缓存；先记录 Trace 次数/耗时、缓存命中、路径重算及未知区域拒绝，再确定格距/刷新频率。本次无游戏采样，**未取得性能或容量数据**，不据现有25Hz频率推断预算足够。

## 剑星原生链：已追到的静态证据

所有地址均为当前 exe 的 RVA，哈希 `573AAFF1C9455F85EA6036EF6F6CCB0774DE4164722A296276FBBFC7FA87545C`。SDK 摘录在 `sdk-excerpts.txt`，SDK 本身不能证明 ABI。

1. `FSBCharacterDetectAITableProperty` 包含水平/垂直视角、近距/高度检测、遮挡规则、Detect/Observe距离、追击距离和仇恨增减值。因此不能采用计划旧代码中的“24格”充当 SB 感知半径。`FSBSkillTargetFilterTableProperty` 另有阵营、形状、状态、战斗模式及 `bValidEngineHandle`；感知半径不是完整目标资格。
2. `SBBTTask_SbDetectTarget` 类注册片段 `2670E18..26710E4` 引用构造 `26749E0`；构造写主 vtable `5C52268`，其 `+2B8` 指向任务实现 `200E210`。该实现读取 SDK 一致的 `+70` BaseBone、`+80/+81` 操作/取消模式及 `+82..+87` 过滤字段、两组效果列表。
3. `200E210` 先检查行为树上下文角色（`rdx+B0`）及 `201C180` 条件，再把检测参数交给 `1A4CA30` 序列化；通过 `201B670` 分配 `RunAITask` 队列对象（vtable `5C51678`），写操作值2、序列化参数与原生角色 `+2198` 的16字节标识，随后入队。**这不是直接给任意 AActor 设 target 的 API。**
4. 队列类型 ID 读口 `1ABF6F0` 和分配器使用同一静态槽 `703287C`；相邻回调 `1ABF700` 通过 server manager `+C8`、`1AACFB0` 解析任务 `+18` 的 ControlGUID，再进入 `1A7FC30`。回调注册/调度线程的完整认证尚缺，不能仅靠相邻关系承诺可调用。
5. `1A7FC30` 有角色状态门禁及18种操作分支；跳表 `1A838CC` 的操作2进入 `1A80E95`，解包同一组检测参数后调用 `1A36190`（`rcx=原生角色+710`）。目标检测函数取得 server manager `+C8`，遍历 `+28/+30` 角色数组并读取候选类型 `+10C`、ControlGUID `+4A4` 等。当前 MC 外观只存在于 UE Mesh 表，不在这份内部角色登记表。
6. `SBCreateCharacter` 字符串相邻函数 `26A9B90` 是 getter 跳板；实际 native exec `26A3B60..26A3E1D` 解析 FName 与4个float、推进脚本游标后直接返回，**没有执行原生角色创建**。排除此调试入口作为可用代理工厂，不调用它。
7. `ServerRequest_ActorLockOnTarget` 的 native exec `275E010` 解析两个 GUID、经 vtable `+1490` 验证再 `+1498` 分派。这是锁定 RPC 包装；不能将它当成已证明的敌人感知/仇恨入口，也不能凭两个整数伪造 ControlGUID。
8. `ClientBroadcast_SkillHitResult` SDK/RPC 可定位现有 GUID 命中广播，但它是命中表现/结果传递的一部分，不能凭收到广播就证明真实原生伤害命中了 MC 目标。现有 ApplyStat 原生链仍只对认证的内部角色生效。

## 未达到的 P0 门槛与下一步

| 前置项 | 当前状态 | 需要补齐的证据 |
| --- | --- | --- |
| MC AI / 导航实际读口 | 离线核对完成 | 未来实现时验证 Mixin 注入和失效停止；当前未改行为 |
| 导航专用数据 | 缺口及契约已明确 | 真正的游戏线程采样与有界性能数据；当前暂停下不采集 |
| 原生可交战代理 | **未认证，生产接入阻塞** | 合法创建/登记/注销的完整角色链，阵营/状态、释放及跨图清理，不冒用 Eve |
| 原生感知和目标延续 | 追到 RunAITask / 目标筛选 | 完整注册、线程、LOS/脱战和实际目标句柄寿命契约 |
| 原生攻击→MC 唯一伤害 | **未认证** | 真实判定命中、准确来源/受害者关联；不能由锁定/动画/距离代替 |
| P1–P4 / P5 | 未实施 / 未构建 / 未实机验收 | P0通过后按原计划顺序实施 |

接续先沿 `1A36190` 的候选资格与角色管理器追合法构造/移除，证明原生命中对该目标类型的处理；再决定是否存在安全兼容代理。尚不能断言当前引擎绝不支持，只能确认现有项目没有已认证方案。暂停期间继续允许离线审计，不要求用户攻击或启动游戏。P0未通过时保持最新已部署盾伤版本和所有原生身份/线程/ABI门禁。

## 验证边界

本轮验证结果见 `verification.json`：18项离线证据/环境核对通过，包含工具语法、错误版本拒绝和静态链关键锚点复核；七份已部署组件哈希一致、QA为0字节。检查时未运行剑星/Java。它们不等同于 AI/导航/双向战斗通过，也不构成 JAR/DLL 新构建。当前暂停要求下不能补取实际动态回调、角色生命周期和命中上下文，原生生产认证保持false。
