# P0续查：原生目标资格与真实配置表（2026-10-15）

本轮仅离线读取已安装 exe、SDK、IoStore。新增14段有界反汇编、17个CALL/指针锚点核对；没有调用游戏函数、创建角色或修改部署。**P0整体仍未通过，不能宣称MC原版AI或双向交战已恢复。**

## 1. 目标资格不是单一阵营或可见性开关

- `1C77590`查观察者的Tribe配置，再进入`2620CD0`的10槽关系查找；Detect候选在`1A365AF`要求关系为Enemy=2。关系有方向，不能自动推定互为敌人。`ESBActorType`的PC=1/NPC=2/Monster=3/Boss=4与CharacterTable数值ID不是同一概念。
- `1A36190`还含生命状态（Alive=1/Spawning=6）、其他状态/隐身、普通或窄感知Alias及几何条件。仅写Tribe或伪造一个GUID不足以得到合法目标。
- `1A36697 → 1C77E20 → 226EDD0`连接几何/遮挡桥。`226EE06`和`226EE38`经`201B1E0`解析两个ControlGUID；缺少manager/GUID/引擎Actor也会在`226EF26`返回0。上层把0解释为无阻挡，因此这个返回值**不能独立证明身份有效或真正执行过LOS查询**。
- 通用技能目标过滤片段`1C73490`检查`FSBSkillTargetFilterTableProperty.bValidEngineHandle`（+66）；启用时必须有ControlGUID。另一个模式参数要求`1C73534`成功查到引擎Actor，并在`1C73542`拒绝Actor+58的bit5。
- `SetActorHiddenInGame`反射exec为`46B3E70`，调用虚表+338；当前网络控制器继承实现为`3F68FB0`，确实写Actor+58 bit5。对应SDK的`AActor.bHidden`。所以“隐藏整个代理Actor但继续作为正常命中目标”与这条过滤路径冲突。组件级可见性、碰撞和真正命中仍需独立确认，不能据此认定可行。

上述过滤取决于具体目标模式；并非断言所有技能都启用同一个开关。

## 2. 资源和owner寿命约束

`FSBCharacterTableProperty`的Tribe/OverrideTribe/条件位于+30/+38/+40；BehaviorTreeRes为+3E8，感知Alias为+3F8/+400，ActorType为+41C，RefAppearance为+420。`1CA92F3 → 1A9A7E0`从Appearance解析CharacterAssetPath（+14），`1CA97C6 → 226DC70`创建对应引擎对象，再进入既有GUID登记链。`226DC70`要求manager及资源路径，并在`226DD52 → 22ADBB0`后执行类相关初始化；不能替换成任意空Actor。

服务端owner保留入口`1BA6D00`委托角色接口+b8（`1A6DE30`），内部还有+c8（`1A6DE90`）和关联角色/配置/世界模式判断。不能简化为HP>0或alive；仍需确认MC代理的保留、消失及切图清理实际行为。

## 3. 已读取真实表内容，仍没有合格代理

资源索引见`resource-index.txt`。使用独立开源[ooz](https://github.com/powzix/ooz)的提交`05038060aa68f9187ae9923b2388ca8db40e58d1`，在临时目录编译离线解码器（MSVC `/O2 /MT /EHsc /D_CRT_SECURE_NO_WARNINGS /FI sys/stat.h`）。没有加载游戏Oodle DLL，也没有把该第三方源码/二进制加入项目或生产DLL。

`tools/inspect_native_mob_tables.py`锁定本机UTOC和SDK哈希，验证解码器哈希后读取三个固定单块资源。依据SDK属性顺序和UE未版本化属性片段布局，完整消费Character **644行**、Tribe **19行**、Appearance **211行**，三表均精确到EOF。格式交叉参考[CUE4Parse的未版本化属性实现](https://github.com/FabianFG/CUE4Parse/tree/master/CUE4Parse/UE4/Assets/Objects/Unversioned)。这是离线格式一致性证据，不是运行时属性/代理资格认证；输出只含显式非零属性，缺失字段不作为已认证默认值。

原始资源/全表JSON只在临时目录，项目仅保存候选相关行、哈希、行数和块元数据：`proxy-candidate-rows.json`、`table-evidence.json`。另曾解压TrainingRoomSettingTable，但数组枚举序列化尚未匹配，**未导出其配置行、未采用猜测结果**；可复现工具明确只解析上述三表。

| 配置 | 实际内容 | 对MC代理方案的意义 |
| --- | --- | --- |
| `N_Dummy` / ID20000 | Tribe=`AttackTribe_All`，NPC；默认效果`BlockFalling_Infinite`，半径配置1，开启距离池 | `AttackTribe_All`自身列出敌对目标，不代表普通Monster反向视其为敌人；Monster关系表没有该Tribe槽。不能因Dummy名称或高HP就选用。 |
| `M_DummyA/B/C` / ID40010/40020/40030 | Tribe=`Monster`，自身行为树`Test/M_Dummy_AI`，HP710/Shield192；各有原生蓝图 | Monster→Monster是Ally，且已有AI。与“原生敌人主动攻击MC、MC独立运动”不相符。 |
| `M_Dummy_Nikke` / ID59700及其变体 | Tribe=`Nikke_Dummy`，Monster；Monster→该Tribe是Enemy，PC→该Tribe是Ally；引用左/中/右墙面蓝图 | 是有价值的阵营线索，但默认效果包括`Passive_Immortal`、`M_Dummy_Nikke_UsePlayerAIPositioning`，还有距离池/半径1。效果名仅是配置事实，行为尚未追证；不能直接用于移动MC代理。 |
| `NST_N_Dummy_01` / ID95050990等 | Tribe=`TribeNoCheck`；带`Check_DummyA`及原版掉落组/生成资源 | 不是纯占位物；普通Monster没有该Tribe敌对槽，且存在额外原生行为配置。 |

尤其要区分`CharacterTable.ID`、行名、Tribe、Appearance.ID和引擎ControlGUID。例如`M_Dummy_Nikke`的Character ID59700引用Appearance ID40040；不能混用。

## 4. 验证与后续门槛

- 目标资格17锚点/14片段通过；原生命周期模式28锚点/36片段重跑通过。
- 四个工具语法通过，错误exe和错误解码器哈希均在输出/执行前拒绝；资源索引工具增加`--match`后，原默认输出与HEAD旧工具逐字节一致。
- 三表有界读取、明确身份交叉核对、唯一行名、合法索引及精确EOF检查通过。没有把EOF匹配当成所有同类型字段语义均获独立认证。
- 七份部署SHA256保持`shield-scale-20261015/manifest.json`基线；QA文件0字节，无剑星/Java进程。详见`verification.json`。

下一步继续离线核对候选蓝图、技能命中/碰撞组件、上述默认效果与角色创建完整参数，寻找不抢占运动、不连带影响Eve/NPC的合法配置。线程、双表寿命、组件隐藏后的真实命中及MC UUID关联仍需动态证据；只有用户明确恢复游戏测试后才能验证。当前不启用P1–P4、不写新QA指令、不放宽ABI/身份/线程门禁。

复现三表（解码器需在项目外从上述提交独立构建；哈希记录只对应本次本地二进制）：

```powershell
python tools/inspect_native_mob_lifecycle.py --qualification --output docs/mob-ai-p0-20261015/qualification
python tools/inspect_native_mob_tables.py --decoder <ooz.exe的绝对路径> --decoder-sha256 <已核查解码器SHA256> --output docs/mob-ai-p0-20261015/qualification
```
