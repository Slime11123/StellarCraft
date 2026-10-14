# 低帧率与重复移动接管日志（2026-10-14）

用户报告帧数极低、日志持续报错。只读采样后实施 Lua 修正，没有启动游戏、发送输入或编写 QA 指令。读取时游戏已经关闭，未替换已加载脚本。

UE4SS 日志中 `MC gravity calibrated`、`MC root motion ignored`、`MC locomotion` 各 1176 次，`creative flight on` 345 次；主要刷屏内容是重复初始化，并非重复的 Lua tick 异常。MC 日志中只筛查错误及 StellarCraft 标记，没有输出启动/认证内容。采样与标量统计见 `UE4SS-before.log`、`log-counts.json`、`stages-and-lifecycle.txt`。原阶段统计中的 transform/mirror 多毫秒耗时只能说明脚本开销，不是实际 GPU 帧率测量。

本次回归由上一生命周期改动引入：每 Tick 从全局注册表取得新的 Controller/Pawn Lua 包装；`isolate_native_input` 却以 `gNativeInput.pc ~= pc` 判断角色切换。同一个原生对象的不同包装会触发恢复输入、重力/RootMotion 校准、StopMovementImmediately 和重新开启飞行，逐帧重复。每帧 FindAllOf 控制器还增加了全局 UObject 遍历开销。

修正：

- 输入接管键改为当前 epoch + Controller/Pawn 原生地址，仅真实身份改变时初始化。胶囊高度和地面忽略列表也按本世界地址缓存，世界失效时仍清空。
- 全局控制器注册表查找只在 REBINDING 使用；ACTIVE 使用当前 epoch 上下文并读取当前 Controller.Pawn，继续核对 Pawn 地址和世界地址。LoadMap/玩家 EndPlay/QuitGame 保留，EndPlay 增加 Controller 失效，阻止引用跨生命周期复用。没有恢复 UEHelpers 跨地图全局 Controller 缓存，也没有重新缓存旧敌人 Actor。
- 切图清空旧 MC 内容、稳定 mapId、实体代次、伤害/ABI 门禁、协议 v6 均保留。敌人全局枚举和原有场景工作本轮未被永久关闭；若修正后仍有低帧率，应依据新 Profile 分别定位，不把本次推断当作全场景性能验收。

离线：新增“同一个原生角色/控制器的 1000 个不同 Lua 包装”回归，旧 `before-main.lua` 在首个不同包装失败，修正版保持同一份 physics 和最初的 RootMotion 保存值；真实 main 的 1000 次 ACTIVE prepare 没有新增全局扫描，Pawn/Controller EndPlay 后重绑仍通过。移动/飞行/F4恢复、台阶、生命周期/旧闭包/同地址重入、唯一 HP/跳劈/箭矢/TNT 路由与 Lua 语法均通过。

仅更换 `main.lua`。安装前核查无剑星/Java，备份 `installed-before-main.lua`，源/候选/安装 SHA256 一致：`CBB283F7F30573FC083E615EFCA7D3058A4F59148C91724A43CF634682CB54E7`。其余六组件与 `map-clear-20261014/manifest.json` 一致，完整新清单见本目录 `manifest.json`。CURRENT_STATE.md/plan.md 已同步。

**尚未获得修正后的实机帧率与日志样本。** 下一步由用户正常启动/操作后只读检查：接管初始化每次真实进入只出现一次、活动帧不再重复开飞行；结合新 Profile 和用户观察判断帧率。助手不代为启动或发送输入，原两类崩溃仍未验收。
