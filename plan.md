# StellarCraft 当前现状、计划与路径

最新 2026-10-12 21:19：Karpathy Guidelines 已加入 AGENTS.md。登录恢复后确认单机敌人的 BroadcastSpawnInfo 为零；以当前 exe 的 ControlGUID → UE 角色地址关联修正目标登记，保留 ABI、线程、GUID/类型、血池和原生无敌门禁。174 处门禁字节变异、Lua 路由/重复事件/双侧 TNT 遮挡/来源歧义回归通过，DLL/Lua 备份安装在 `candidate-2118/`；哈希见 CURRENT_STATE.md。普通大沙漠已发布 5 个实际敌人；尚未攻击，不算伤害/盾牌通过。21:04:38 退出崩溃再次发生，dump 保存在 `candidate-2105/exit-210438.dmp`，未上传。下一步普通区域真实武器/弓箭/TNT/盾牌和五项联合验收，然后普通无 QA 收尾；首领不测，不重查既有验收。

最新 2026-10-12 20:55：继续接续时两游戏均已关闭，Steam 位于登录界面；需用户自行登录后继续实机，不操作认证窗口。01:12 已部署第二候选，但剑星第二轮尚未成功加载。实际 Lua 新回归通过部分 TNT 遮挡公式（0.5 × 0.5 → 9.75）与三次来源键冲突拒绝，未改部署组件。QA 0 字节；01:11:32 退出崩溃 dump 已备份到 `docs/combat-integration-20261012-002957/candidate-0112/exit-crash-0111/`，未上传。剩余依次为普通敌人登记诊断、真实武器/弓箭/TNT/盾牌、此前五项联合验收和普通无 QA 收尾；首领不测，编译/模拟不能替代实机。

最新 2026-10-12 01:12：用户完整方案已实施 v4 候选，构建/离线回归通过；证据、首轮哈希及实现限制见 `docs/PLAN_NATIVE_COMBAT_2026-10-12.md`。普通区域实测恢复，首领不测。首轮桥旁可见普通敌人但登记目标表为空，未攻击；正常退出两游戏后已备份部署诊断及局部修正，第二轮重启测试中。当前 main.lua `4DA0A3B2F9BC0A86819ABB3E3DC0F1A904D96220BF8797D984BBE0CB3D1F818A`，JAR `5DC1C1CFC0C185FE4A1C78D3FF44BC6B85ACFE0260F384E20B92D27EEF45E3F3`，其他 DLL/addon/native_world 同 v4 首轮。下一步定位拒绝原因，再完成真实扣血/盾牌和五项联合测试；没有实机战斗通过结论，普通无 QA 收尾未完成。已验收隔离/纹理/击退/鞘翅显示不重开。

最新 2026-10-12（伤害扩展接续）：用户要求先参考“实现 MC 伤害剑星怪物”计划实现武器/弓箭/TNT 对原生敌人的比例伤害及盾牌格挡，再与五项待测一起实测；此要求恢复普通区域测试，首领实测限制不变。引用对话读取只返回正文占位符，已询问用户粘贴最终计划及换算/格挡规则。独立源码审查确认：现有 Lua 使用 MaxHP × MC 原版伤害 / 类别预算（20/40/100/200/200）；原生伤害身份、ABI、线程校验保留；DamageBridge 入站伤害使用 mobAttack(null)，没有来源位置，原版盾牌方向判断无法正常工作；TerrainTargetMixin 显式重选实体未包含 ActorProxy，隐藏 MC 地形可能导致原生敌人无法选中；代理尺寸当前固定，原生命中精度待补；TNT 对原生敌人已有转发但尚未完成真实敌人验收。本次尚未修改伤害源码、构建、部署或启动游戏，进程核查未见剑星/MC。下一步获取方案正文后落实这些缺项，联合测试此前已部署五项；不重开已验收问题。

最新 2026-10-12 00:14：上轮鞘翅显示已获用户验收，当前五项任务已按用户粘贴的最终修正版实施、构建并备份部署：原版弓状态/拓扑代次与安全像素合并、登记箭矢及主机裁剪后命中、后台 GUI Shift、TNT 原版序列粒子与闪白黑烟、逐实体受击外观/姿态。实现、离线回归、哈希及待测限制详见 `docs/PLAN_VISUAL_COMBAT_2026-10-12.md`。JAR + block_visuals/native_world/native_particles 源与实际部署一致，v3 布局和门禁保留。五项实机未验收，实际帧时间/内存和原生敌人代理命中精度待测。备份 `docs/visual-combat-five-20261011-234333/`；两游戏关闭、QA 0 字节，本轮未操作游戏，电脑操作暂停要求仍生效。下一步普通区域验证五项新行为；首领不测试。伤害/贴图/击退及用户已验收的鞘翅显示不重新定位。

最新 23:35：用户确认鞘翅可飞行，第三人称偶发静止长方体仍为当前显示缺陷。已修复模型更换沿用旧局部基底插值的问题；旧代码回归失败，新代码模型更换/同模型平滑/100 次切换、身体身份和 Lua 语法通过。用户正常退出两游戏、MC 23:34:43 全维度保存，核查进程后备份并部署 `block_visuals.lua`，源与部署 SHA-256 `52CECDEAC2C22D43B81ABCC9351B527D2630BFE19C8554C77D9250DA9C80382A` 一致；备份 `docs/elytra-visual-20261011-2334/`。显示修复尚未实机验收。下一步用户确认第三人称起飞/滑翔/着陆时翅膀是否稳定；若仍复发，检查原版装备层捕获的部件身份及立方体/单面识别，不回查已验收伤害/贴图/击退。两游戏关闭，QA 指令清空，电脑操作未恢复，不自动启动；普通无 QA 启动仍待确认。

最新 23:23 接续：伤害、贴图、击退已由用户实测验收，不重复排查。本轮按用户提供的九步“移动、生物动画与鞘翅整合”方案实施，见 `docs/PLAN_MOVEMENT_ELYTRA_2026-10-11.md` 的逐步状态、构建证据和部署哈希。协议 v3、原生运动意图/碰撞反馈、原版滑翔与烟花接入、生物步态/头部、共享内存动态姿态及 UUID 门禁已构建安装；离线通过不代表实机通过。ReShade 输入组件也必须重建为 v3，保留版本检查；23:22 已安装并重启普通区域测试。首领仍不测试。下方旧现状与部署哈希保留为历史，新接续以本节及 CURRENT_STATE.md 顶部为准。

最新 19:25：用户已完成伤害实测并确认没有问题，伤害规则按用户验收通过，不再重复排查。转为参考“代码审查与修复计划”修复人物/生物/手部贴图及登记 MC 怪物击退。尸壳导出 PNG 64x64 完整，六部件每个224–384 texel quads；大沙漠实际父材质为 Restroom/006_A_MI，存在随地图变化。已改为主动加载此前希雍验证的 Furniture_003/039_B_MI，并禁止纹理流式降级；成功怪物近战的 vanilla 击退速度传入受控 MC 本地移动，保留原生胶囊、隐藏生物隔离与伤害规则。JAR 构建/remap、Lua 语法/身体身份回归通过；已备份并安装，贴图/击退实机仍待验证。两个游戏此前已退出，全维度19:21:55保存。QA文件已清空。

更新时间：2026-10-11 18:11（Asia/Shanghai）。当前接续以 CURRENT_STATE.md 和本节新增伤害任务为准；下方旧验收保留，不重复排查。

### 本轮新增任务与证据

- 用户已恢复普通区域测试，仍不测试首领。当前优先：MC 模式保护 Eve；MC 生存人物承受剑星敌人和登记 MC 怪物伤害；创造免伤，Eve 模式正常。
- ActorProxy 继承方法漏映射已修复并加构建校验；13:41 大沙漠成功载入 MC 敌人代理。
- 新 Eve 原生保护/伤害转移通道和登记 Monster 基础追逐/近战已编译部署，仍待实机伤害验收。保留游戏线程、exe ABI、GUID、类型、指针与时效门禁；只允许登记且原生反馈新鲜的 MC 怪物。原生视线阻挡攻击，未实现完整 MC AI。
- 新通道首次实测发现 Java 状态切片越界；17:41 修复为独立 24 字节预留区映射，边界/半写帧回归通过，17:46 大沙漠加载成功，17:47:20 全维度保存。
- 17:46 PlayerGuard 未激活，未接近敌人。17:48 正常退出剑星时新崩溃：UE4SS.dll+0x71B7DF 访问异常，已备份 `docs/player-damage-20261011-135450/exit-crash-174831/`。该退出崩溃尚未定位；不据此断定大沙漠切图崩溃已修复或同源。
- 18:03 Lua 地址比较确认本机 Pawn/控制器对应；18:06 只读采样发现原生 PlayerId=0，而 EventorActorGUID=选中玩家 GUID=228、type=100、HP=3500。改用经 exe Init/日志加载锚点认证的本地世界玩家 GUID 关联；保留全部线程、指针、类型、血池和时效门禁。242 处字节变异拒绝检查通过，18:09 安装，真实伤害待验收。只读证据：`docs/player-damage-20261011-135450/identity-readonly-1808.txt`。
- 18:01:26 退出访问异常另行备份；Lua 改为仅 MC 控制时读取玩家对象后，18:08 一次正常退出无新崩溃，尚不宣称修复。
- 当前部署 SHA-256：DLL `D3CC5D9E7DF212E567D5495AAEE07AF6B4E2D95469974114F00EEFDC91B144E3`；JAR `B7FA305D206E5E9B614BC7DFBF1DBBD22724D99AEC50CA16AC9CC5A5BB7BC968`；main.lua `1C50A92EC6E0C4438FA6B6B2D01E9D2DC0B245EB8D1F9389739CEB7185B8179F`；native_world.lua `86EBACAF740E044F40350344D59330D32F97A49AC66B98E61940B117701AEC96`。下方旧哈希为历史部署。
- QA 文件已清空；当前 MC 停在标题，此进程带 devQa，普通无 QA 重启收尾尚未完成。无新 TNT/怪物放置。

## 1. 历史暂停点（13:19，已恢复；本节进程状态不是当前状态）

- 原 13:19 暂停点保留为历史记录。**本轮用户已明确恢复普通区域实测，仍不测试首领模式。** 用户补充进入大沙漠在 Eve 模式崩溃；优先定位切图阻塞，再在大沙漠进行真实敌人 TNT 伤害验收。
- 当前没有检测到 SB、StellarBlade、java、javaw 游戏进程。Minecraft 日志在 13:17:36 确认停止单人服务器并保存所有维度。本次记录没有再启动游戏或发送游戏 UI 输入。
- 本地 QA 指令文件已清空，长度为 0。最后指令 `441 hold forward 100` 于 13:17:25 执行，13:17:30 已完成释放输入。
- 最后一次画面位于希雍 Xion 的入口桥，MC 模式、创造模式；最后导航操作为 yaw=205、pitch=12 后前进 100 tick。前进后的最终位置没有截图确认，恢复时重新观察，不能复用旧窗口句柄/截图坐标。
- 当时正准备前往普通战斗区域，补测 TNT 对真实剑星敌人的伤害。**该项尚未实测。**
- 用户已说明自己曾将人物移到台阶，不需要继续调查重复输入或把那次位置变化当成自动输入异常。

## 2. 目标与约束

- 在独立副本中维护 Minecraft ↔ 剑星 DX12 的进程内集成；保留原 WorkBuddy 项目。
- 不恢复外部 overlay 窗口。MC 方块、人物、手部、实体和粒子使用剑星 UE 世界渲染，参与原生深度、灯光及阴影；HUD 使用剑星进程内 DX12 合成。
- MC 模式使用 MC 移动意图，地面、墙体、重力和移动结果由剑星胶囊及碰撞决定。隐藏 MC 世界的方块、生物、流体和位置修正不能干扰受控角色。
- 方块/实体身份持久化；只复制玩家在剑星投放并登记的内容。后台 MC 实体不加入渲染和爆炸伤害名单。
- TNT 只破坏登记 MC 方块；伤害白名单是登记 MC 实体和确认的剑星敌人，排除 Eve、NPC 与剑星场景。
- 首领模式 F4 崩溃按用户要求先列计划，不在本轮进入首领模式测试。
- 用户已授权日常修复、构建、安装及自动测试，不重复索取批准；当前暂停指令优先。

## 3. 当前实现与验收

| 项目 | 当前状态和证据 |
| --- | --- |
| 移动、转向、跳跃、创造飞行 | 此前用户确认转向/移动、跳跃和双空格飞行已解决；保留原生碰撞、上升/悬停/下降及关闭飞行。 |
| 台阶 | 胶囊前沿探测，抬升→前进→落地扫掠，上限 60 cm；下降时保留 180 ms 支撑，墙、低顶、陡面仍拒绝。09:32 五级金属阶约 0.7 秒通过，旧版约 2.5 秒卡住。12:54 桥中央前进约 16 米 / 4 秒、跨约 1.3 米高差；返回约 17 米 / 4 秒，同步序号保持 106，无拉回循环。侧护栏碰撞不属于台阶失败。 |
| 单击放置与 F4 重入 | 12:56 单击只放一个 TNT；Eve→MC 往返后 12:57:22 再放成功。贴近玩家胶囊的目标会拒绝放置，远一点成功。 |
| 方块存档与旧后台方块 | 登记方块保存完整 block state 与原生坐标原点，重启后恢复 actor。未登记的历史后台方块不参与移动或瞄准；无法凭现有登记恢复它们的旧位置。 |
| 像素贴图、HUD | 使用 MC 原版贴图和真实 alpha，保留黑色像素边缘，移除 MC 暗角；窗口化与分辨率随剑星匹配。若再次出现 UI 模糊或边缘缺失，需对应分辨率实机复查，不能只依据日志验收。 |
| 手部、人物、蹲姿 | 提高姿态采样并做原生插值，抑制后台 MC 世界渲染。Shift 实测 CROUCHING、脚底稳定；手部与角色模型由剑星渲染。用户此前确认走路手部动画正常。 |
| 后台生物伤害隔离 | 09:15 生存模式隐藏僵尸攻击 hurt=false，生命 20→20。受控角色绕开 MC 后台碰撞/挤压/位置修正。 |
| MC 实体与船 | 猪和橡木船实机可见 MC 几何、像素贴图及剑星阴影。当前为基础漫步、地面船交互，**未实现完整生物 AI 或水面航行**。最近 16 个实体、48 米范围、25 Hz 原生碰撞反馈。 |
| 实体超距冻结 | 每个 UUID 独立检查反馈时效，超过 500 ms 停止隐藏 MC 位置模拟。13:07 玩家飞到 Y=256.197、地面约 204.6，超距测试猪在两次采样中的坐标相同；不是仅检查全局反馈文件新鲜度。 |
| TNT 方块与 MC 实体 | 单个 TNT 点火/引信正常；实测删除登记 MC 方块并炸毁登记猪/船，玩家生命 20，剑星桥面完整。 |
| TNT 粒子 | 13:09:50 安装双面网格、扩大可见尺寸和 12 个预热复用粒子。13:13 画面中看到灰白像素爆炸粒子扩散并消失；不是只凭 actor 创建日志判定。单次 12、活跃上限 24，隐藏回收池上限 12。 |
| 剑星敌人爆炸伤害 | 当前 DLL 改为经过当前 exe ABI 校验的原生 HP 入口；只有实际 HP 下降才报告成功。**仍待普通区域真实敌人伤害、死亡及非敌人排除实机验收。**旧文档 GameplayStatics 描述已过时。 |
| MC 发光方块 | 火把实机照亮剑星地面；火把/萤石 PointLight 创建，最多 64 盏、24 米有效范围、关闭灯光阴影；熄灭/破坏/重同步处理生命周期。 |
| 末影珍珠 | 原版初速度，剑星碰撞命中；检查可落脚面和胶囊空间后传送。09:18 同步序号 3、新坐标和画面移动均确认成功。 |
| 性能 | 桥面 MC 模式采样约 123–154 FPS；13:13 粒子池版本爆炸前约 146–150、爆炸时读数约 90、随后约 147 FPS。事件处理峰值约 2 ms，旧粒子冷创建约 33 ms。仅为这些场景瞬时读数，不保证所有区域最低帧率。 |

### 已执行的检查

- Fabric 编译及 named→intermediary remap、共享内存 DLL 编译通过。
- 共享内存 6000 次发布、原生地面双槽 4000 次、CRC/半写帧/进程归属及序号边界检查通过。
- Lua 移动、跳跃/飞行/蹲姿/F4、40 cm 阶/70 cm 墙/低顶/陡面与原生世界回归通过。
- 原生世界回归覆盖异世界帧拒绝、实体过期/清理、灯光生命周期、真实 MC 相机朝向、粒子尺寸/双面、冷创建不消耗寿命、池复用、世界重置释放。
- 原生伤害门禁验证当前 exe 的 86 处关键字节及无效地址拒绝；**门禁通过不等于真实敌人受伤验证通过**。

### 已知未解决或有限制

- 普通区域真实剑星敌人 TNT 伤害仍未验证，下一轮优先。
- 12:39 出现 GLFW.dll 原生崩溃，旧记录也有同类；后续重启稳定，但没有证明已修复。这次不是首领模式崩溃。
- 首领模式 F4 崩溃仅有排查计划；未宣称修复。
- 未完成所有区域的稳定帧时间测试、所有生成蛋实体、完整 MC AI、船浮力及水上航行。
- 最后 Eve 模式画面出现红色线框，来源尚未确认；若恢复时仍有，检查测试可视化是否残留，不应直接归因于 MC 模型。

## 4. 当前接续计划（用户已恢复普通区域实测）

- 本轮预检查：DLL、JAR、main.lua、native_world.lua、block_visuals.lua 源与部署哈希一致；启动前无游戏进程，QA 文件 0 字节。13:33 Steam 启动剑星，MC 未启动，保持 Eve 模式，准备一次受控切图复现。
- 存档/原日志/12:05 minidump 备份：`docs/desert-crash-20261011-133345/`。12:05 GameThread 栈顶 UE4SS+0x71b7df，二进制核对为 UObjectBase::GetInternalIndex 内部读取访问异常；该旧记录与用户报告的关联尚未确认。先收集此次切图记录，不凭旧栈推定具体模组根因。
- 13:36:45 用户与亚当交谈成功切入 `E05_GreatDesert_P`；13:37 确認大沙漠 Eve 画面及 MC/java/javaw 未运行。成功日志保存为 `docs/desert-crash-20261011-133345/UE4SS-desert-without-mc.log`。未修改代码，不能宣称切图崩溃已修复；待对照 MC 已启动/原生对象已激活时的切图路径。先在当前大沙漠启动 MC 并观察，再接续敌人伤害验收。

1. 重新确认安装文件、进程及新窗口，读本文件和修复记录顶部；恢复普通存档，观察实际位置，避免沿用旧截图坐标。不要检查用户已解释的重复输入。
2. 经正常地图/营地/旅行 UI 到大沙漠；先定位并处理用户报告的 Eve 模式切图崩溃。稳定进入后，在明确的敌人旁放一个 TNT 并点火，记录敌人 GUID、HP 前后、距离和事件日志；验收敌人掉血/死亡，同时 Eve、NPC、剑星场景不受此桥接伤害。遇到 ABI 不匹配、无效 actor 或异常入口停止原生调用，先修相应生命周期/身份判断。
3. 针对实际发现的问题修复，不因已通过的项目重复展开无关测试。若敌人场景表现卡顿，按实体数量、粒子、姿态、事件耗时和帧时间定位；保存对照数据，不用单张 FPS 截图声称全局解决。
4. 补充生成蛋/船交互与光源破坏/熄灭等尚缺的实际路径；完整 AI 和水上船功能需明确实现范围后再扩展。
5. 正常结束 QA：清空指令文件，正常保存并退出测试 MC，用不带 `-Dstellarcraft.devQa=true` 的普通启动重新进入；剑星仍在运行时不要重复启动。恢复正常物品/模式，确认没有残留按键或已点燃 TNT。当前游戏已退出、指令已清空；正常启动的验证还未执行。
6. 更新验收记录，区分实机通过、仅编译/模拟通过和待验收，向用户交付简明结果。

### 首领模式 F4 排查计划（仍只规划）

- 同一切换时间收集 Java crash-report、hs_err、UE4SS 日志和世界/pawn/控制状态，区分 Java 异常、原生 GLFW 崩溃及进程退出。
- 普通地图与首领模式对照 actor proxy、属性注册、渲染器、世界标识与角色更换；检查失效对象调用及资源重建。
- 定位具体失败点后才改生命周期逻辑；后续验收首次 F4、往返、死亡/重试与退出首领模式，并保留普通存档放置和飞行检查。

## 5. 路径与文件

### 根目录

| 用途 | 绝对路径 |
| --- | --- |
| 当前工作区 | `C:\Users\StickySlime\Documents\Codex\Projects\StellarCraft` |
| 实际独立项目源码（下文 P） | `C:\Users\StickySlime\Documents\Codex\Projects\StellarCraft` |
| 原项目 | `C:\Users\StickySlime\WorkBuddy\2026-10-07-12-54-48` |
| 参考仓库目录 | `C:\Users\StickySlime\Documents\Codex\2026-10-08\c-users-stickyslime-workbuddy-2026-10\outputs\StellarCraft-project\reference` |
| 剑星二进制目录（下文 S） | `D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64` |
| Minecraft 实例（下文 M） | `D:\Release 2.7.4\.minecraft\versions\1.20.1-Fabric 0.19.5` |
| 剑星存档 | `C:\Users\StickySlime\AppData\Local\SB\Saved\SaveGames\76561198823003429` |
| 构建 JDK | `C:\Users\StickySlime\jdk-21.0.12.1+1` |
| MC 运行 Java | `E:\1Minecraft\Java21` |

下面的 `P`、`S`、`M` 是上表路径的文档缩写，不是已设置的环境变量。

### 关键源码、工具与证据（相对上述根目录）

| 路径 | 用途 |
| --- | --- |
| `P\sb-mod\lua\main.lua` | 模式切换、原生移动/台阶、方块放置、敌人代理及事件处理。 |
| `P\sb-mod\lua\native_world.lua` | 原生实体碰撞反馈、光源、爆炸粒子及池生命周期。 |
| `P\sb-mod\lua\block_visuals.lua` | MC 模型、材质、贴图、网格及粒子双面显示。 |
| `P\sb-mod\src\native_combat.h` | 原生敌人 HP ABI、身份/版本/线程/地址门禁。 |
| `P\sb-mod\src\shm_bridge.cpp` | 共享内存与 Lua 原生导出。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\world\NativeEntities.java` | 登记实体、物理、TNT/珍珠、每 UUID 反馈超时。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\world\NativeWorldEvents.java` | 世界事件桥接。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\world\PlacedBlocks.java` | 登记方块、完整状态和存档原点持久化。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\world\NativeQa.java` | 本地可选 QA 指令，默认禁用。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\client\NativeMovement.java` | 原生移动意图、跳跃/飞行/蹲姿。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\client\NativeDisplay.java` | 窗口化与分辨率同步。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\client\InputBridge.java` | 键鼠与宿主模式输入。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\render\` | 姿态、实体/手部几何与 HUD 导出。 |
| `P\mc-mod\src\main\java\dev\stellarcraft\world\mixin\` | 后台伤害/碰撞隔离、放置、投射物、HUD 等。 |
| `P\mc-mod\build-dx12-jar.ps1` | Fabric 编译与 remap，输出 `P\mc-mod\build\libs\stellarcraft-0.1.0-dx12.jar`。 |
| `P\sb-mod\build.ps1`、`P\sb-mod\编译shm.bat` | 本地原生构建入口；先读脚本确认当前目标与工具链。安装使用 `P\sb-mod\build\manual\stellarcraft_shm.dll`，不要使用 sb-mod 根目录的旧零字节占位 DLL。 |
| `P\sb-mod\reshade-addon\install.ps1` | 备份、安装 DLL/Lua/JAR；替换前正常保存并退出占用游戏。 |
| `P\sb-mod\tests\native-world-regression.lua` | 世界、实体、光源、粒子池回归。 |
| `P\sb-mod\tests\native-step-regression.lua`、`native-movement-regression.lua` | 台阶与移动回归。 |
| `P\sb-mod\tests\native-combat-gate.cpp` | 当前 exe 原生伤害门禁检查。 |
| `P\sb-mod\tests\read-live-state.ps1` | 只读 `Local\StellarCraft_v1` 状态；游戏退出后 mapping 可能不存在。 |
| `P\docs\REPAIR_2026-10-11_NATIVE_WORLD.md` | 本次详细修复及实机过程，顶部为最新验收。 |
| `P\docs\PLAN_2026-10-11_NATIVE_WORLD.md` | 世界扩展顺序、首领模式排查计划。 |
| `P\docs\stairs-bridge-centre-20261011.csv`、`stairs-bridge-return-20261011.csv` | 桥中央往返通过数据。 |
| `P\docs\stairs-user-position-forward-20261011.csv` | 初次贴护栏碰撞数据，不能与中央测试混为一谈。 |
| `P\docs\stairs-live-stall-20261011.csv`、`stairs-live-fixed-climb-20261011.csv` | 早期五级金属台阶卡住与修复后对照。 |
| `P\docs\entities-range-freeze-20261011.log` | 超距实体冻结的过滤 QA 证据。 |
| `P\sb-mod\reshade-addon\deployment-backups\20261011-130950` | 最新 13:09:50 部署前备份。 |

### 安装、日志、QA 与参考

- 剑星 Lua：`S\ue4ss\Mods\StellarCraft\Scripts`；共享内存 DLL：`S\ue4ss\Mods\StellarCraft\stellarcraft_shm.dll`；模型/反馈资源：`S\ue4ss\Mods\StellarCraft\block-assets`。
- 剑星日志：`S\ue4ss\UE4SS.log`（日志时间 UTC，换算北京时间 +8）。
- MC 部署 JAR：`M\mods\stellarcraft-0.1.0.jar`；存档：`M\saves\新的世界`；日志：`M\logs\latest.log`；崩溃报告：`M\crash-reports`，原生崩溃文件通常为实例中的 `hs_err_pid*.log`。
- QA 指令：`M\config\stellarcraft-dev-qa.txt`，当前为空；仅临时以 `-Dstellarcraft.devQa=true` 启动才处理。此前最后 ID=441，恢复 QA 使用更大的 ID。普通启动不带该属性。
- MC 现有启动入口：`D:\Release 2.7.4\PCL\LatestLaunch.bat`。启动参数/批处理/完整 stdout 或 hs_err 可能包含认证资料，**禁止原样输出或复制进交接文件**；仅摘取需要的无敏感日志。
- 历史启动输出位置：`C:\Users\StickySlime\Documents\Codex\2026-10-08\c-users-stickyslime-workbuddy-2026-10\outputs\mc-relaunch.stdout.log` / `mc-relaunch.stderr.log`，同样不要整份输出。
- 剑星通过 Steam 启动，appid `3489700`；MC 自动启动链可能检测/启动剑星，不要重复启动。
- 只狼参考：[Xu060113/SekiroCraft-Passthrough](https://github.com/Xu060113/SekiroCraft-Passthrough)，本地 `reference\SekiroCraft-Passthrough`。
- 剑星原生伤害参考：[stellar-blade-control-panel](https://github.com/thetotaladdiction-arch/stellar-blade-control-panel)，本地 `reference\stellar-blade-control-panel`。

## 6. 当前部署版本指纹

最新安装：2026-10-11 13:09:50；安装时已核对源文件与部署文件哈希一致。以下为当次记录，下一次安装需重新核对。

| 文件 | SHA-256 |
| --- | --- |
| JAR | `DA810D8400625246BB373D93B6F1DBCE3EA1D714446211FEB8C19A42859F648F` |
| native_world.lua | `1E5CB90514EDF5A9DA7DCF14497BD8BEE461C3F983D49995F69F506482D261CE` |
| block_visuals.lua | `6C94BBBE7BD39C454D541DB47E0E15A615CC4975295B434C9F918D793CC1B4E1` |
| stellarcraft_shm.dll | `48CF8F933B7336BAB941B9BB00A7CB9780224BCC1C45C3A28E436C680EA30CCB` |
| 当前剑星 exe（伤害 ABI 对应版本） | `573AAFF1C9455F85EA6036EF6F6CCB0774DE4164722A296276FBBFC7FA87545C` |

原生伤害只在游戏线程使用经过门禁的 server stat HP 路径，Eve GUID 作为来源；拒绝无效 GUID、Eve/type=100、无效血量及身份，SEH 异常会锁停。不同 exe 版本必须重新验证，不能照抄偏移放开校验。

