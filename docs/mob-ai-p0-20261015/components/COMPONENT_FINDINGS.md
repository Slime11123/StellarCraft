# P0第四轮：代理默认效果、姿态与组件证据（2026-10-15）

**仍未找到可直接认证的MC战斗代理，不启用P1–P4。** 本轮没有修改游戏资源、生产逻辑或部署，没有启动/操作游戏；游戏测试暂停保持。

## 默认效果已追到配置和消费者

在上一轮三表之外，离线完整解析EffectTable **4227行**、CharacterStanceTable **252行**、TargetFilterTable **1866行**，均有界读取并精确到EOF。仍采用锁定版本的SDK顺序解释未版本化属性，仅导出相关非零字段；这是静态格式一致性证据，未证明运行时全部字段/行为。

| 候选默认效果 | 实际配置 | 本轮可确认的后果 |
| --- | --- | --- |
| `Passive_Immortal` / ID2001100 | Self，LifeType=3（Infinite），ActorState1=27（Immortal） | `1A64E82 → 1A64E8A`确实查询27；`1A64E93`取常量1.0，`1A64EA8`与原有最低HP取max，再限制HP写入。此路径不是完全拒绝伤害，而是保持至少1HP。另一路`1BB13AC → 1BB13B4`在27存在时跳过后续分支；该分支完整业务尚未认证。 |
| `M_Dummy_Nikke_UsePlayerAIPositioning` / ID5970013 | Self，Infinite，ActorState1=104 | `1BE60C7 → 1BE60CF`查询104计数；大于0时`1BE60D8`取管理器+C8，根据+3C索引从角色数组+28/+30替换局部角色rdi。已证明改变该定位流程的参考角色，尚不能把它扩张成“所有移动都会跟随Eve”。 |
| `DisableMonsterWarp` / ID5000056 | Self，Infinite，ActorState1=96 | SDK对应DisableMonsterWarp；具体传送/位移消费者本轮未认证。 |
| `BlockFalling_Infinite` / ID2000602 | Self，Infinite，ActorState1=10 | SDK对应BlockFalling；不能据名称假定它能兼容MC跳跃/跌落。 |
| `Off_HUD` → 表中`Off_Hud` / ID6200007 | Flag=Hud，Self，Infinite，ActorState1=38 | 别名按FName的大小写不敏感语义匹配。修正了最初精确字符串匹配会误报缺失的问题。SDK状态38为HideHUD；未将其当作Actor隐藏或碰撞关闭。 |

状态存在/计数查询为`1A64B40`/`1A64C10`，读取角色状态容器，并非凭借枚举名称推断效果已执行。新增5段有界反汇编，核对5个CALL、2个状态ID字节和1个浮点常量，共8锚点。地址均为当前固定exe的RVA，不能用作已批准ABI。

这些结果缩小了Nikke代理的风险范围，但不能仅删除两个效果后认定剩余角色安全；创建、运动、状态、受击、死亡和双表清理仍需整体认证。

## 姿态和目标模式还有额外限制

- `M_Dummy_Nikke`姿态ID597000明确设置`bImpossibleLockOn`和`bImpossibleAutoLockOn`，并启用significance优化；这不是原生敌人一定不能攻击的证明，也不是可直接用作代理的依据。
- `N_Dummy_Default`姿态ID200000同样禁止锁定，CommandArray还包含多个`N_Dummy_BeachExplosion...`命令。命令名是配置引用，未证明会自动触发，但排除了“它只是无行为空壳”的假设。
- 1866个目标过滤配置中，4行显式启用`bValidEngineHandle`，均为Eve Tachy SwordAura相关模式；通用`Enemy`行没有这个显式非零字段。上一轮的引擎句柄/隐藏过滤必须结合具体模式，不能外推成所有技能统一开启。其余模式不启用此开关也不等于可以跳过引擎身份：原生感知、几何及另一模式参数仍有独立查表条件。

## 蓝图和碰撞证据的边界

离线解压WallLeft/Center/Right及普通Dummy四个实际蓝图包，保存哈希、块索引和筛选后的name map：

- WallLeft记录`Wall_Mesh`、StaticMeshComponent，以及`/Game/DLC_2/Art/BG/Actor/NK_Barricade/001_A_SM`等真实资源引用。
- Center/Right记录Left的类/默认对象名称，均有`CollisionGroupComponent`、CharacterMovement、CharacterMesh0等名称。
- 普通Dummy也记录CollisionGroup、Capsule和SBSkeletalMeshComponent。

**这里只解析名称引用，没有解码蓝图属性、继承覆盖值或图执行。** 尤其不能把出现`ECollisionEnabled::NoCollision`这个名称当作某个组件当前配置为NoCollision，更不能据“有CollisionGroup名称”认定有有效受击形状。

匹配SDK显示`ASBCharacter.CollisionGroupComponent`在+5C8，组件的`pCollisionData`在+C0、`CreatedShapeComponent`在+D0；`USBCharacterCollisionData`分别保存碰撞形状、受击组、攻击组及动画变换。受击资格需要这些真实数据/创建状态和角色身份共同成立。组件级隐藏能否保留真正命中还没有证据，不修改生产碰撞开关来试成功。

## 验证、文件与下一步

证据：`table-evidence.json`、`component-candidate-rows.json`、`lifecycle-disassembly.txt`、`sdk-lifecycle.txt`、`lifecycle-evidence.json`、`verification.json`。资源路径见`resource-index.txt`。原始资源、全表和第三方解码器只在临时目录；项目只新增取证工具/筛选证据。

工具入口：`inspect_native_mob_tables.py --components`和`inspect_native_mob_lifecycle.py --components`。解码器来源、版本和构建方式沿用[上一轮记录](../qualification/QUALIFICATION_FINDINGS.md)，本轮只增加必要的Vector/Rotator与效果结束条件类型解释；没有引入通用资产导出框架。

验证包含新增8锚点、六表EOF/行名与索引、别名大小写解析、错误exe/解码器拒绝和旧默认三表结果保持；七份部署哈希和空QA另行复核，均不代替实机验收。

下一步离线追创建完整参数、蓝图组件属性/碰撞数据的真实关联及原生effect受害者到MC UUID的唯一事件映射。当前最有价值的是Nikke阵营规则；墙面蓝图、无限状态和定位参考切换仍不满足可直接采用的门槛。线程/双表寿命/实际感知与命中仅在用户明确恢复后验证，P0整体仍未通过。
