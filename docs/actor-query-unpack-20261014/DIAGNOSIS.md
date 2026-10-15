# 当前地图查询返回值解包与伤害回归（2026-10-14）

用户报告上轮扫描优化后无法伤害原生敌人。日志显示查询得到 50–102 个候选，但所有周期均 nearby=0/published=0，且有敌人向玩家造成原生命中。没有 world character query unavailable：引擎查询成功，但结果未成为可攻击 Actor。原伤害比例、ABI 和身份门禁不重开/不放宽。

匹配本机 UE4SS d3d1004 的 `LuaUObject.cpp` 明确显示：数组回填循环（约715–730行）对每个元素使用 Operation::GetParam，而 ObjectProperty 的该分支（约413–415行）构造 RemoteUnrealParam；必须立即调用 `param:get()` 才得到 UObject。上轮只核对了外层数组回填，漏掉内层元素包装，直接把参数对象交给 Actor:IsValid；pcall 静默拒绝，故候选数量非零但敌人完全不登记。旧离线替身直接返回 Actor，未模拟真实包装，漏测了这一回归。

本轮在当前 World 查询内部立即解包每个结果、验证得到的 UObject，再返回普通 Actor 数组。解包前不调用 Actor 方法，参数包装不存入目标表或跨帧保存；目标/来源表仍仅存地址、GUID、类型、epoch，命中时继续当前 World 查询与身份复核。不恢复全局扫描、不改查询频率、不恢复旧 Actor 缓存，切图清空、EVE 控制恢复和伤害/时效/线程门禁均保持。

回归替身现按该版本行为返回只能 :get() 的参数包装，其 IsValid 故意抛错，防止以后又把参数当 Actor。旧 `before-main.lua` 在包装查询、完整登记/命中回归均失败，记录 `baseline-query.txt` / `baseline-combat.txt`；修正版通过 1000 次零全局扫描、空/换图/失效/异常查询、完整敌人发布和命中复核、唯一 HP/跳劈样式/箭矢/TNT/来源与过期对象拒绝。运动/F4、台阶、生命周期旧任务与同地址重入、Lua 语法通过，见 `regression.txt`。SDK/匹配源证据沿用 `eve-stutter-20261014/LuaUObject.cpp`，补充的函数数据声明在本目录 `LuaUFunction.hpp`。

核查无剑星/Java、QA 0 字节后，仅备份/安装 main.lua。源/候选/部署 SHA256：`D330A12373CE5C7EF63880F13B21E081ADC5CE6F7891212675F437EFCE6088E6`。备份 `installed-before-main.lua`，完整清单 `manifest.json`；DLL/JAR 等六组件保持原配套版本。未启动游戏、发送输入或写 QA 指令。

**实际恢复扣血未实机验收。** 下一步由用户正常启动/操作后只读 candidates/nearby/published 与唯一 Combat HP 日志，确认目标重新登记和伤害恢复；不以离线替身通过代替实际扣血。用户此前确认当前复测不再崩溃继续保留；EVE 卡顿优化的实测效果同样不冒充已通过。
