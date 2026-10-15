# P0第五轮：创建参数与命中身份（2026-10-15）

本轮10段有界反汇编、14个CALL/指针锚点通过。仅离线读取固定版本exe；不代表ABI、线程、对象寿命或代理配置已获运行时认证。P0整体未通过，SB主动攻击MC仍不接入生产。

## CreateActor：请求字段、分支和完成回执

SDK的`ServerRequest_CreateActor`有10个显式参数。`23B8550`实现将它们写入请求对象；下表中的栈偏移以实现函数**入口RSP**为基准，包含隐含this（RCX），不是可直接调用的函数声明。

| 参数 | 实现入口来源 | 请求字段 |
| --- | --- | --- |
| CharacterTableID | EDX | +10 uint32 |
| Position | R8指向12字节Vector | +14..+1F |
| Rotation | R9指向12字节Rotator | +20..+2B |
| IsPlayer | [RSP+28] byte | +2C |
| CheatCmd | [RSP+30] byte | +2D |
| PlayerId | [RSP+38] int32 | +30 |
| EventSpawnId | [RSP+40] uint32 | +3C |
| SpawnIndex | [RSP+48] byte | +40 |
| SpawnPointName | [RSP+50] 8字节FName | +34 |
| Possess | [RSP+58] byte | +61 |

实现还初始化内部字段：+41=0、+42=1、+48=1、+50容器清空、+60=0、+64处8字节清零。它们并非SDK中的额外用户参数；其全部语义尚未认证，不给未知位强行命名。

`1B097F5`起的请求执行分支显示：

- 内部运行模式值为1时进入完整owner创建路径。
- 其他相关模式下，IsPlayer=1或CheatCmd=1也进入完整路径；普通false/false请求走`1B09854 → 1C1C9A0`，以EventSpawnId、SpawnIndex、位置/旋转、请求内部配置和Possess处理事件出生。
- 完整路径`1B099ED → 1AD1290`则使用CharacterTableID、位置/旋转及共15个含this的参数，包括上述内部字段；再由`1AD1438 → 1BA6D20`初始化owner。**不能把SDK的10个参数直接套成owner工厂ABI，也不能把普通false/false请求理解为总是按TableID生成角色。**
- 未根据这些分支开启CheatCmd、伪装玩家或接管Possess来“强行成功”；当前模式的运行时取值、创建失败与成功对象仍未验证。

回执片段`1B09A15..1B09A54`仅在请求+C非零时分配完成packet，将请求ID写packet+10，按返回指针是否非零写packet+14布尔，再广播。**这一回执没有提供新角色GUID。** 仍需从原生登记/owner成功路径获得并复核身份，不能把请求ID或bool当作代理ID。

## 效果ID不等于攻击序号，stat回调不独立证明物理命中

`1BCA980..1BCAA70`按0x18字节目标记录循环。r15保持同一效果对象；通过其虚表+38取得效果ID，将它作为第8参数传给`1BA8720`，然后推进目标记录继续结算。效果虚表`5C0BD18+38 → E4D660`确实返回对象+20。

因此同一个效果ID可参与多个目标结算。按效果ID全局去重会漏目标；只加目标ID也未证明能区分无限/周期效果的多个合法结算，不能作为已完成的攻击去重设计。

`1BCD318..1BCD38B`还显示stat类别来自效果配置+164，并非固定HP。`1BA8A28..1BA8AA1`在特定owner关联/标志下递归调用`1BA8720`。这些静态证据说明该入口承担通用stat/effect工作，不能把每次负stat变化都标为一次新的物理攻击。

既有受害/来源关联仍应保持：

1. 服务端`1BA87C1`按第8参数查当前效果对象；该对象+104转换为传入stat函数的raw source key。**raw key、effect object ID和攻击者GUID是三个不同标识。**
2. effect+58是受害owner，+60是来源owner；owner+60才是原生角色。新Mob通道需要同时复核当前owner登记表、角色GUID/type/ControlGUID、目标UUID/ID和地图代次，不能复用“地址相等”作为全部资格。
3. 服务端stat调用返回点`1BA8823`与客户端重放`1B1F82B`不同；只能选定权威来源发布，不双发。
4. 独立递增传输序号只能处理重传/乱序；不能自动证明原生技能命中、合并同一击的多个stat或识别合法持续伤害。实际命中阶段和归一化MC伤害仍未认证。
5. 当前Eve保护hook只接受watched/type100等既有门禁，本轮未修改它。也未创建Mob事件、扩大来源白名单或更改v6协议。

## 接续边界

创建的安全角色配置、内部参数完整语义、对象产生/清理线程，以及真正命中到MC UUID的唯一关联仍缺动态证据。继续保留门禁，不用默认Dummy、作弊/玩家身份、假GUID、近距离定时伤害绕过。

另一方面，原方案P0门槛主要阻止未认证的SB→MC接入；MC端可独立完成的NoAI来源保存不需要调用上述原生入口。本轮已另行实施该P1准备项，见[AI_STATE_IMPLEMENTATION.md](../ai-state/AI_STATE_IMPLEMENTATION.md)。它不表示完整P1或P2–P4完成。

复现：`python tools/inspect_native_mob_lifecycle.py --bindings --output docs/mob-ai-p0-20261015/bindings`。锚点与片段见`lifecycle-evidence.json`、`lifecycle-disassembly.txt`，检查结果见`verification.json`。
