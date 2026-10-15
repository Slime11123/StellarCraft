# P1准备项：保存原始NoAI来源（2026-10-15）

已实现、构建、离线回归并只安装新JAR。**原版AI仍未开启，旧手写运动/战斗仍未移除，完整P1/P2–P4均未完成；未实机验收。** 游戏测试继续暂停，没有启动/操作游戏或写QA指令。

## 实现

`NativeEntities.ENTITY_LOAD`原先直接调用`mob.setNoAi(true)`，新生物保存后只剩强制值，后续无法区分原本的AI状态。本轮在登记前记录是否已有`stellarcraft.native`，在现有setNoAi前调用最小工具类`NativeMobAiState.captureTag`并通过实体原有Tags保存来源：

- 新登记且原NoAI=false：`stellarcraft.ai.original.noai_false`。
- 新登记且原NoAI=true：`stellarcraft.ai.original.noai_true`，保留NBT/命令禁用意图。
- 原已登记但没有来源标记：`stellarcraft.ai.original.unknown`，不猜测历史状态。
- 已有该前缀标记：不覆盖；未知、损坏或冲突标记不会被自动改成可开启AI。

标记沿用原版实体Tags序列化，无新存档格式/全局表/协议字段。实体标签容量不足而addTag失败时，不能据此宣称来源已保存；未来恢复逻辑必须继续把无有效标记的旧实体视为未知。当前没有新增恢复/读取/开启AI逻辑，也不重写不相关标签。标记只代表首次抑制前的值；登记后的新命令NoAI变更仍需完整P1另行处理，不能拿初始值覆盖后来的用户设置。

其他运动、攻击、掉落、地图epoch、生命周期和Eve保护均未改动。本轮没有读取或改写真实MC存档。

## 验证

- `NativeMobAiStateTest`使用实际NBT类型和NbtIo进行内存序列化/重载：分别从原NoAI=true/false开始，持久保存强制NoAI=true后，来源不被重载值覆盖；legacy、unknown、损坏/冲突标记和用户/epoch标签保留通过。测试不是运行Minecraft客户端/服务端。
- Fabric 1.20.1 JAR编译/remap通过；实际成品含新工具类，反汇编确认captureTag/addTag在`class_1308.method_5977(true)`之前，且仍传true。
- 既有实际Java命中包、跳劈、世界事件代次、地图账本/旧生物清理回归通过。
- 安装前无剑星/Java，七份组件均核对上一部署基线，QA0字节。只备份替换JAR，安装后源/候选/部署SHA256一致，其余六组件保持原哈希。

新JAR SHA256：`B80FF7867A8E49B1A74BEA46B56368620731557488E28ECD3746C4958C222379`。

清单与回退：`manifest.json`、`candidate/stellarcraft-0.1.0.jar`、`before-install/stellarcraft-0.1.0.jar`。原部署JAR哈希`A767AFBA7371B27BE3B775538A6AC7422F4247268B308AD1F45459B417A884FF`；DLL仍为`626E3F44A667969362B88D1E9D360D3A2C645A2F73440DDE90E2F1EA13704199`。实际保存/重入尚未实机验证，不能以NBT回归或构建通过替代。

下一步可继续实现MC侧AI恢复/导航的独立环节，但开启原版AI必须同步处理导航虚拟地形、可信反馈、旧手写运动冲突、击退和NoAI后续命令；当前不单独解除NoAI。SB主动交战仍受P0原生代理门槛约束。
