# StellarCraft：新对话入口

此目录包含完整独立项目、构建产物、部署备份、验收记录及当前任务指引。

## 加入 Codex

项目名建议使用 **StellarCraft**。在 Codex 添加本地项目/文件夹，选择：

```text
C:\Users\StickySlime\Documents\Codex\Projects\StellarCraft
```

将该文件夹设为项目主文件夹，然后从项目中创建新对话。根目录 AGENTS.md 提供新对话指引；详细方式见 [OpenAI 项目文档](https://learn.chatgpt.com/docs/projects)。

当前工具没有创建 Codex 侧栏项目的接口，本次完成的是本地完整项目准备；侧栏注册需在应用中添加该文件夹。没有创建或触发新对话，没有恢复游戏测试。

## 新对话直接粘贴

仅查看现状：

> 读取 AGENTS.md 和 CURRENT_STATE.md，再按需查看 plan.md。基于当前状态继续讨论，不重复排查已验收的问题；游戏测试仍保持暂停。

明确恢复测试时：

> 读取 AGENTS.md 和 CURRENT_STATE.md，按 plan.md 的剩余计划继续。现在恢复自动游戏测试，优先验证 TNT 对普通剑星敌人的伤害，不重复排查已验收的问题。

## 文件说明

- AGENTS.md：长期约束与读取顺序。
- CURRENT_STATE.md：简短现状、已验收项目和剩余待办。
- plan.md：完整交接、验收证据、路径和计划。
- docs/：详细过程与 CSV/log 证据。
- mc-mod/、sb-mod/、protocol/、tools/：源码、构建与工具。
- 历史 Forge 交付包、旧构建和备份保留供追溯；当前维护的是 Fabric 1.20.1 + 剑星 DX12 方案。

复制检查：6851 个原项目文件全部存在且大小一致；当前 JAR、实际共享内存 DLL 和三份关键 Lua 的 SHA-256 与原副本一致。未修改游戏安装目录。

参考仓库与历史启动日志仍在原工作区，其绝对路径记录于 plan.md。后续代码与状态更新以本项目为准，不在两个源码副本间交替修改。
