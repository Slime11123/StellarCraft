# EVE 模式移动卡顿（2026-10-14）

用户明确确认当前复测不再崩溃，本轮只处理切回 EVE 后移动间歇卡顿，不重开原崩溃定位。助手只读日志/修改文件，没有启动游戏、发送输入或新增 QA 指令。

当前日志中，F4→EVE 后相机已恢复，EVE transform 通常平均 0.02–0.04ms；mirror 却出现 55–77ms 峰值，扫描间隔为 200ms。空/少角色地图同样有十多毫秒扫描。证据见 `UE4SS-before.log`、`profile-evidence.txt`。这是周期停顿的明确线索，不是证明卡顿只有这一原因，也不等于取得 GPU 帧率数据。

原 `collect_character_actors` 的 Level.Actors 反射快路径不可用时，退回 `FindAllOf("Character")`，在游戏线程扫描全局 UObject 注册表；主循环及命中重新定位均会使用该函数。当前 UE4SS d3d1004 源码 `LuaMod.cpp:1077` 确认 FindAllOf 转交全局查询。为避免恢复不安全的跨帧 Actor 缓存，本轮改用 `GameplayStatics:GetAllActorsOfClass(currentWorld, CharacterClass, outTable)` 获取当前 World 的新引用，涵盖加载的子关卡，保持 5Hz 发布频率和 EVE 联动。

本机 `CXXHeaderDump/Engine.hpp` 确认三参数签名，匹配版本 `LuaUObject.cpp:181–270` 及数组 pusher 确认 Lua `{}` 出参会被原表回填；本目录保存了匹配版本源文件用于静态审查。没有使用其他 Lua 插件的同名 API。库/类无效或查询抛错时清空目标表并进入原有限频错误日志，不退回昂贵的全局扫描，不用旧 Actor 顶替。所有伤害/身份/线程/时效/epoch 门禁保持，切图清空与上轮输入恢复修正保持。Profile 现在也输出原已计时但漏显示的 nativeWorld 阶段，便于后续区分剩余实体/粒子开销。

离线结果见 `regression.txt`：真实函数连续 1000 次当前 World 查询零全局扫描；空/切换/改变结果与查询失败无旧对象/重扫描；失败时目标表清零；已有唯一伤害、跳劈/箭矢/TNT/来源、运动/F4恢复、台阶及生命周期回归通过。旧版函数在同一测试失败，记录于 `baseline-regression.txt`。Lua 语法通过。**新引擎查询的本机反射执行和卡顿改善尚未实机验证。**

确认无剑星/Java后只备份/安装 main.lua，源/候选/部署 SHA256：`3E3B1D28EBF467CACF2068190C98A43A058529340DAB42E519629620E1339A32`。备份 `installed-before-main.lua`；DLL/JAR等其余六组件、协议 v6、空 QA 文件不变，完整清单 `manifest.json`。

下一步由用户正常启动/操作后只读新 Profile，对比 EVE mirror 峰值与移动观感，并检查是否出现 world character query unavailable。若仍卡顿，按新证据查看 nativeWorld/publish 等阶段；不靠关闭全部 EVE 联动掩盖，不重新恢复旧世界对象缓存。用户确认的不再崩溃作为当前复测结果保留。
