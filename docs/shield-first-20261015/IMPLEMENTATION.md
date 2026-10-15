# MC 命中原生敌人：盾优先修复交付

日期：2026-10-15。按 `../PLAN_MC_NATIVE_SHIELD_FIRST_DAMAGE_2026-10-14.md` 的 P0/P1/P2 实施，已备份安装，游戏测试仍暂停。

## 行为和实现

- `native_combat.h` 每击从当前 owner/entity 同时读取 HP、MaxHP、Shield、MaxShield。有盾只调用一次原生 `ApplyStatDiff(stat=7)`，扣量 `min(currentShield, mcDamage * nativeMaxHP / budget)`；本击破盾/超额破盾均不扣 HP。下一击重新读到 Shield=0，才走原 HP 结算。无盾槽仍扣 HP，恢复护盾后重新走盾路由。
- 原生 stat=7 setter 已二次静态确认：`1A6512A` 分支读取 `+134`，在 `1A65178` 读取 `+138` 做上界钳制，原生自己执行整数截断和写入；stat=8 跳表进入 `1A651C9` 更新 MaxShield。桥接不写这些内存，不补偿整数取整，也不重放零盾别名事件。
- 在既有 exe/线程/身份校验之外增加 stat 分派、stat 池、Shield setter/状态分支、跳表索引/目标和零盾效果分支的 438 字节锚点。原有免伤及死亡/异常状态继续拦截。所有池须 finite/范围合法，盾读取异常不会被视为零盾。
- 原生调用后重新查 owner/entity、GUID/type/vtable/sourceKey，读回池；盾路由必须 HP 不变。拒绝/读回失败/身份变化不补发 HP 调用、不准备 Show。区分查询异常与原 HP 死亡卸载的空返回。
- `Result` 保留 HP 三字段，增加盾三字段及 `damageRoute`；`applied` 表示已确认的合法槽下降。Lua 私有结果表和日志补充路由、HP/Shield 前后、GUID/type/world/epoch/serial，返回的 nativeDamage 按路由取损失。
- `native_hit_context` 接受存活目标的 HP 或 Shield 真实下降，保留单次 serial、时效、身份、方向与武器门禁。未改 Show 资源、Boss/强击限制或未认证 Timed HitStun。
- 相对旧安装 main.lua 的改动仅为伤害日志/路由返回与调用；先前 RemoteUnrealParam 解包、Boss 入伤加成和 nativeWorld profile 保持。Java/JAR、公开 v6 协议布局及其余五组件未变。

## 验证证据

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| 本机 exe 静态字段/分派/零盾别名 | 通过静态核对，非动态调用验收 | static-disassembly.txt；`sb-mod/tests/native-shield-evidence.py` |
| 真实生产 C++ 结算函数 | 原生 lookup/apply 换成桩，其余门禁/读取/结算/重读为实际代码 | combat-gate.txt；native-shield-settlement.h |
| C++ 矩阵 | 80→55、10→0不溢出、下一击HP、无盾、回盾、多目标/类型/预算、有限极值、死亡、免伤、读取异常/不可访问盾字段、换身份、错线程/版本、SEH锁止通过 | combat-gate.txt |
| 版本/字节门禁 | 战斗/Show 3427 次逐字节变异、玩家/效果 995 次逐字节变异及既有负例拒绝 | combat-gate.txt、player-gate.txt |
| 盾损失动画上下文 | 普通/跳劈/箭矢/TNT存活上下文、单次消费/过期拒绝通过；不声称动画已显示 | combat-gate.txt |
| Lua 真实源码 | shield-only伤害事件去重/重击/箭矢/TNT、Boss入伤门禁、1000次World查询、旧对象/epoch/F4、移动/台阶与语法通过 | lua.txt、conversion.txt |
| 实际候选 DLL/Lua | 新结果字段/拒绝路由导出、v6地图发布、事件时间/环回/单次消费通过 | dll-protocol.txt |
| DLL 构建 | 成功 | build.txt |
| 普通有盾敌人实机 | 未验证 | 游戏暂停 |
| Boss 破盾/换相实机 | 未验证 | 未启动Boss测试 |

桩的 zeroEvents 只证明桥接发出一次 type=7 调用且没有后续 HP/第二次伤害；实际零盾效果、HUD、换相与动画必须由实机证据判断。原生 stat=7 ABI 的参数和字段已静态核对并经本地桩回归，尚无新动态调用验收。

## 部署、回退与下一步

- 安装前确认无剑星/Java，QA 文件0字节，旧七组件与上一清单一致。仅替换 DLL 和 main.lua；源/候选/部署 SHA256 完全一致。
- DLL：`4193F7273D97D979128906AE96101CF95ADDC06D1649BDD4C12F114ED15A523F`。
- main.lua：`3EAF419F1FB60DECE99ACBA844BA190D79A78D2F8691AD24FEC7DA202786FA71`。
- `manifest.json` 含两组件源/候选/部署及旧哈希，`before-install/` 保留旧 DLL/main.lua，`candidate/` 保留本次安装文件。回退应在相关游戏正常退出后一起恢复两旧组件并核对 previousSha256。
- 未启动游戏、发送游戏输入或写入 QA 测试指令。下一步等待用户明确恢复测试后，先验证普通有盾怪逐击盾/HP/HUD与破盾表现，Boss操作仍须遵守授权范围。既有重击全身动画/Timed HitStun未验收状态不变。
