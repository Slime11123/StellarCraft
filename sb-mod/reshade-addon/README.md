# StellarCraft：剑星 DX12 进程内 MC UI

## 当前状态

2026-10-09 已构建、部署并在本机剑星与 Minecraft 1.20.1 Fabric 中实测。
独立项目位于 `outputs/StellarCraft-project`，原 WorkBuddy 项目未修改。

外置 `overlay.exe` 的启动逻辑、源码、构建脚本和当前游戏目录中的可执行文件已移除。
当前渲染入口是剑星进程内的 `StellarCraft.addon` DLL；不创建透明、置顶或分层窗口。
首次安装保存了旧文件用于恢复，备份不参与运行。

## 工作方式

1. 剑星加载官方 ReShade 6.8.0 的完整 add-on 版 `dxgi.dll`。
2. ReShade 加载同目录下的 `StellarCraft.addon`，注册 DX12 渲染回调。
3. 插件读取原有 `Local\StellarCraft_v1` 共享内存，将 MC slot-0 像素上传到 DX12 纹理。
4. `StellarCraft.fx` 在剑星交换链画面中合成 MC HUD、菜单和软件光标。
5. DLL 内的输入线程将键鼠事件写入现有输入环，发布 `EDIT` / `NATIVE_UI` 状态。
   游戏窗口消息过滤器阻止操作 MC 时同时触发剑星菜单；F4 仍交给原 Lua 切换逻辑。

纹理上传使用三个上传缓冲区与 GPU fence 管理复用。MC 写帧使用奇偶序列号，读取端拒绝
写到一半的帧；共享内存协议仍为 v2，原字段布局保持兼容。帧图像的上下翻转和 GLFW
鼠标坐标分别处理，避免菜单点击位置倒置。

## 构建

### DLL

在普通命令提示符运行本目录 `build.bat`。需要 Visual Studio 2022 Community 的 C++
工具链。产物为 `build/StellarCraft.addon`，它是 x64 DLL。

ReShade SDK 头文件已固定在 `third_party/reshade/include`，来源提交为
`7bf9de8b33bcc76c3177007e65d73c72dd0f34c0`，许可证随文件保存。

### MC JAR

本机完整 Gradle 构建在编译前因 Java Windows Selector 回环 socket 的
`Invalid argument: connect` 错误失败。备用脚本使用 JDK 21 编译输入、方块、手部及
Mixin 改动类，用 TinyRemapper 按本机 1.20.1 mappings 从 named 转为 intermediary，
再合入原发行 JAR。新增手部 Redirect 使用显式 intermediary selector。

可重复执行：

```powershell
& ..\..\mc-mod\build-dx12-jar.ps1 -JdkDirectory 'C:\Users\StickySlime\jdk-21.0.12.1+1'
```

产物为 `mc-mod/build/libs/stellarcraft-0.1.0-dx12.jar`。此脚本依赖复制项目中保留的
原发行 JAR 与已有编译类，不能替代从空目录完成的全量 Gradle 构建。

## 安装与启动

本机已经安装到：

- 剑星：`D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64`
- MC：`D:\Release 2.7.4\.minecraft\versions\1.20.1-Fabric 0.19.5\mods`

关闭剑星与 MC 后，可用安装脚本重新部署：

```powershell
& .\install.ps1 -ReShadeRuntime 'C:\Users\StickySlime\Documents\Codex\2026-10-08\c-users-stickyslime-workbuddy-2026-10\work\reshade-dist\ReShade64.dll'
```

必须使用支持 add-on 的完整 ReShade runtime。脚本备份已有 runtime、配置、插件、
着色器和 StellarCraft JAR 后再替换，备份位于 `deployment-backups`。

启动 MC 与剑星，进入剑星存档，让原连接逻辑就绪，再按 F4 切换到 Steve。
MC 菜单、背包等界面内可使用鼠标，E 打开/关闭背包，Esc 返回，F4 返回 Eve。
剑星加载或原生菜单期间插件暂停转发。ReShade 设置页快捷键为 Home。

保持 `PerformanceMode=0`，插件需要逐帧修改 FX 参数。刚启动时若出现一次
`technique was not loaded`，随后出现 `Successfully compiled` 与 `FX compositor ready`
即表示异步加载完成；持续未就绪才需要检查 `EffectSearchPaths`。

## 已验证与范围

详见 `verification/2026-10-09.md` 与同目录日志摘录。

- 插件进入剑星进程，DX12 初始化、共享内存连接、FX 编译和像素上传成功。
- MC 主菜单、选项、世界列表、已有创造世界热栏和背包均显示在剑星画面内。
- 鼠标点击菜单、背包分类定位、E 开关背包、Esc 返回与 F4 往返切换通过。
- 操作 MC 的 Esc 不再同时打开剑星自己的菜单。
- 已部署 DLL 和 FX 的 SHA256 与独立项目产物一致；两个改动 Java 类内容一致。
- 游戏目录无 `overlay.exe`，实测无 overlay 进程。

当前继续使用旧导出器的“黑色像素透明”规则，因为它输出的 alpha 为零。
纯黑 UI 像素和软边透明仍受此规则限制，预留深度层尚未参与合成，剑星原有 HUD
仍可能显示在 MC UI 背后。本次验证覆盖 UI 渲染与输入切换，没有完成战斗、
摄像机控制、长时间稳定性或性能基准测试。后续验证见新的专项验证记录。

## 原生像素方块与第一人称手部

本次现场验证见 `verification/2026-10-09-native-pixels-hands.md`，包含截图、日志与安装哈希。

- 方块从 MC 当前资源包 baked model 导出真实面片、UV 和贴图，剑星生成
  ProceduralMeshComponent；原生 Cube 保留 BlockAll 碰撞。
- 每个原贴图像素对应一个面片，UV 恒定于像素中心。剑星共享线性 sampler 不会
  混合相邻像素。透明像素不生成面片；原 PNG 的像素和尺寸保持原样。
- 手臂、袖层、持物姿态、挥手与使用动作从 vanilla ItemInHandRenderer 捕获为几何。
  Steve 模式不向 MC GPU buffer 提交手部，也不导出 lightmap/overlay 光照。
  剑星 CameraActor 下的原生网格通过环境材质接收剑星场景照明。
- MC 相机关闭景深和运动模糊，保持近处像素清晰；F4 返回 Eve 恢复原视角。
- 手部二进制帧 `block-assets/hands.frame` 按 SCHAND3 原子替换，最多约 30 Hz。
  每个原模型面缓存像素网格，逐帧只传位置、四元数、缩放。换物品才重建。菜单、隐藏 HUD、退出 Steve 或帧
  超时会隐藏手部。皮肤和物品材质从 MC 已加载的真实纹理导出并缓存。
- 地图、附魔光泽、模组自定义非四边形渲染及高分辨率资源包尚未全面验证。
  手部使用剑星世界深度，贴近墙面可能被遮挡。

16x16 立方体最多 1536 个像素面片，材质按纹理共享；不为每个像素创建 Actor。
每条面片轴限制 128 格，限制复杂资源包的几何数量。
