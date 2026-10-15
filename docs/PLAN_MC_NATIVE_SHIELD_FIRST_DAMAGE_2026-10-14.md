# MC 武器命中剑星敌人 / Boss：先扣原生护盾，再扣 HP（修复计划）

> 数值更新 2026-10-15（用户已要求执行）：盾伤改按实时 MaxShield / 档位预算，HP公式保持不变，替代最初同一HP换算系数的要求。仅DLL已备份安装，离线样本通过；新数值尚未实机验收。详见 [数值修正交付](shield-scale-20261015/IMPLEMENTATION.md)。

> 实施更新 2026-10-15：P0/P1/P2 已实施，DLL 构建、生产结算桩/门禁/Lua/DLL 回归通过，已备份安装配套 DLL+main.lua。普通敌人/Boss 破盾效果与动画实机均未验证，游戏测试暂停保持。证据、限制和回退见 [交付记录](shield-first-20261015/IMPLEMENTATION.md)。下方 2026-10-14 的“仅计划”说明为原始审查时点。

> 2026-10-14 代码静态审查 + 当前本机 exe 只读反汇编。当前交付为计划，不修改战斗代码、不构建部署、不启动游戏或发送测试输入；Boss 实机测试仍未获授权。

## 1. 现象与结论

- MC 近战 / 弓箭 / 登记 TNT 命中剑星普通敌人或 Boss 时，现有桥接优先扣原生 HP，不检查、也不扣原生 Shield。
- 已确认直接原因：`sb-mod/src/native_combat.h::actor()` 计算 `MaxHP * min(mcDamage / budget, 1)`，然后 `invoke_apply(base, owner, amount, cause)` 固定传 `ApplyStatDiff(..., statType=1, delta=-amount, ...)`。`1` 是 HP。
- 链路：`mc-mod/.../DamageBridge.java` → MC 命中环 → `sb-mod/lua/main.lua::apply_events()` → `damage_actor()` / `enemy_stats()` → `sb-mod/src/shm_bridge.cpp::sc_enemy_damage_lua()` → `native_combat::actor()`。`damage_applier.cpp` 是未接入的 skeleton，不是实际修复入口。
- 原有换算说明 `tools/hp_conversion.py` 第 254–256 行已明确写了「同一个 k，MC 伤害先扣护盾、破盾后再扣血」，但实现只处理 HP；属于设计与实现不一致。

## 2. 已核对的原生数据与仍须验证的事项

- `sb-mod/src/SDK/SB_enums.hpp`：`ActorStatType_HP=1`、`ActorStatType_Shield=7`、`ActorStatType_MaxShield=8`，另有 `EffectCalculationType_ShieldDamage=2`；两组枚举不是同一个参数，不能混用。
- 当前 exe 只读反汇编：`ApplyStatDiff` 入口 RVA `0x1BA8720`，在 `0x1BA8893–0x1BA88A8` 区分 `HP=1`、`Shield=7`、`Stamina=12`；`Shield=7` 的 `0x1BA8968` 分支读取 shield，并在清零时进入零盾效果别名路径。这是静态证据，不等于动态调用已验证。
- 当前原生 stat 池起始 `entity+0x118`，已有 HP `entity+0x11C`、MaxHP `entity+0x120`；反汇编读取 Shield `entity+0x134`，`MaxShield` 候选 `entity+0x138` 依赖同一 stat 索引布局，实施前用本机原生 getter/读表进行二次静态确认。
- `SB.hpp::FSBCharacterTableProperty` 有 `MaxShield`、`ShieldAttackPower`、`ShieldBlock`、`EffectAliasWhenZeroShieldArray`；HUD `WB_MainHUD_EnemyStateBar` / `WB_MainHUD_EnemyInfo` 分别有 Shield/MaxShield 显示属性。**不能**把 UI 数值或 ShieldBlock 当权威状态回写。
- 必须进一步认证 type=7 的实际 ABI、应用后重读、零盾触发、角色异常状态/分阶段 Boss 的门禁；正式生产调用之前做静态字节锚点与负例校验。

## 3. 明确的目标语义

1. **有盾目标（shieldBefore > 0）**：本次 MC 命中只对原生 Shield 结算；此次攻击不扣 HP，包括本击恰好破盾或超额破盾的情形。即「本击破盾，下一击才可扣血」，无溢出穿盾。
2. **当前没有护盾（shieldBefore == 0 且原生数据有效）**：保持已有按 HPMax / tier budget 的 HP 伤害，原生死亡和掉落路径不替换。
3. **没有护盾槽的敌人（maxShield == 0）**：维持已有正常 HP 伤害，不使普通小怪免疫。
4. **护盾恢复或 Boss 新阶段生成护盾**：每击重取该目标当前原生 stat，优先盾，不复用上一击的 0 值。
5. **2026-10-15 用户已变更并执行**：护盾伤害改用 `rawShieldDamage = mcDamage * liveNativeMaxShield / TIER_BUDGET[tier]`，实际扣盾 `min(shieldBefore, rawShieldDamage)`。HP继续按实时MaxHP换算，不新增可调倍率；原同一HP换算系数要求已被替代。高难度、NG+和换阶段都重取实时上限。
6. 不将盾值异常、身份失败、stat 读取失败、错误线程、错误版本当作「护盾为零」；这些情况必须拒绝当前命中，**禁止回退直接扣血**。

## 4. 最小实施改动

### P0：认证原生 Shield 的读取和原生 stat=7 调用（优先）

- 在 `native_combat.h` 原 `certified()` 锚点之外，增加 Shield 索引分支及其零盾效果路径的本机 exe 指令锚点；认证 `entity+0x134/+0x138` 的 float 范围、finite、`0 ≤ current ≤ max`，并核查换相/死亡时地址复用。
- 改造 `invoke_apply()` 为限定的 `invoke_apply_stat(base,owner,statType,amount,cause)`，允许 `statType` 仅为 `HP=1` 或 `Shield=7`；保持现有调用线程、构建指纹、owner→entity GUID/type/vtable 和原生保护状态门禁。
- 在仅 Shield 路由下调用 **一次** `ApplyStatDiff(type=7, delta=-min(currentShield, rawShieldDamage))`，重取 owner 与实体身份并读回 Shield/HP；验证 HP 没下降，读取异常就停止，不额外执行 HP 扣减；不给自己手写护盾数值，不跳过原生零盾事件。

### P1：按「盾优先」重构伤害结果，但保持协议不变

- `native_combat::Result` 在保留 `before/after/maximum` = HP 含义的同时增加 `shieldBefore/shieldAfter/shieldMaximum`、`damageRoute`（shield/hp/refused）和 `applied`（任意合法原生槽实际下降）。使调用方无须推断「applied 就等于 HP 下降」。
- `native_combat::actor()` 先取 HP+Shield 的同一快照；Shield>0 只扣盾，Shield=0 只扣 HP；非有限、过期或异常数值 fail-closed；无盾目标不会走 stat=7。
- `shm_bridge.cpp::sc_enemy_damage_lua()` 在 Lua 结果表补盾前后和 route 字段；`main.lua::damage_actor()` 日志分别打印 HP / Shield 变化。保留原有伤害序号、session/world/epoch、MC PID、目标重登记、来源校验、遮挡与伤害去重。
- 现有 `main.lua::damage_actor()` 返回值 `nativeDamage` 只差 `HPbefore-HPafter`，应改为按路由汇报扣掉的 shield 或 HP，并标注类型；不然会出现「护盾实际下降但日志伤害=0」的假象。

### P2：修复 shield-only 受击动作回归

- `native_hit_context.h::prepare()` 当前要求 `damage.after < damage.before` 且 HP 仍活着才能生成动画上下文。盾优先后该条件会拒绝 **所有仅掉盾的轻击/跳劈**。
- 改为 `target HP after > 0` 且 `hpLoss > 0 || shieldLoss > 0`，其余时效、有效身份、命中方向、武器分类、native_show 门禁全部保留；每个 MC 事件最多一次原生命中 Show。
- `native_hit_show.h` 的当前强击入场限制（包括 `budget>100` 返回、HitStun 未认证问题）属于现存独立限制，**不为破盾临时放开**；不得整段重放原版 SkillDamage/HitStun 以免产生第二次伤害。

## 5. 离线验证矩阵与验收门槛

| 案例 | 本击 Shield | 本击 HP | 必要断言 |
| --- | ---: | ---: | --- |
| S=80, H=1000, raw=25 | 80→55 | 1000→1000 | 护盾优先 |
| S=10, H=1000, raw=25 | 10→0 | 1000→1000 | 一击破盾不溢出，原生零盾事件只触发一次 |
| 下一击 S=0, H=1000, raw=25 | 0→0 | 1000→975 | 破盾后进入 HP 路由 |
| 无护盾槽 Smax=0 | 0 | 正常减少 | 普通无盾敌人不无敌 |
| S>0 但 Shield 读回失败 | 不确定 | 不变 | 不许 fallback HP |
| 重复同一个 MC 命中序号 | 不变 | 不变 | 不发生二次扣盾 / 扣血 |
| 护盾恢复或 Boss 换阶段 | 按实时新 Shield | 有盾时不变 | 不沿用旧 target/phase |

- 加入针对真实 `native_combat.h` 结算的 C++ 隔离桩/函数注入测试，检测 type=7 或 1 的唯一分发、事件不重复、有限数值/溢出、拒绝类型、ABI 版本锚点错误；不能仅通过 Lua 假 actor 测试证明已修复。
- 扩展 `sb-mod/tests/native-combat-routing.lua`、`tools/test_damage_conversion.py` 及 `native_hit_context` 回归，覆盖轻击/跳劈、箭矢/TNT、无盾、破盾、护盾回满、多敌人、死亡、F4/地图 epoch、非游戏线程及 Boss 类型。
- 先通过本地无游戏编译和离线测试。**游戏实测仍暂停，Boss 模式未经授权不启动；** 后续如用户明确恢复测试，先普通有盾怪验证，最后由用户操作 Boss。实机验收需要记录同一次命中的 shieldBefore/After、hpBefore/After、route、GUID/type/world/serial，并观察原生 HUD、破盾效果、死亡/换相、轻击/重击受击动画。

## 6. 交付与风险

- 涉及主要文件：`sb-mod/src/native_combat.h`、`sb-mod/src/shm_bridge.cpp`、`sb-mod/src/native_hit_context.h`、`sb-mod/lua/main.lua` 和对应测试。**优先不改 MC Java/JAR 和共享内存 v6 协议。**
- 不修改 `damage_applier.cpp` skeleton；不直写敌人血量/盾量内存、不伪造 UI、不可为修好普通怪放宽敌人/玩家/世界身份及游戏线程验证。
- 需明确区分「原生 stat=7 静态分支确认」「Shield ABI/字段经回归认证」「普通敌人实机通过」「Boss 实机通过」四个阶段；当前仅前者有静态反汇编证据。
- 当前工作区有用户未提交修改：`CURRENT_STATE.md`、`docs/REPAIR_EVE_MAP_TRAVEL_CRASH_2026-10-13.md`、`sb-mod/lua/main.lua`、`sb-mod/tests/native-combat-routing.lua` 及新增文件。本轮**未覆盖、重置或重排**这些修改，后续实施须基于当前 diff 做局部变更。
