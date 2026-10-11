# 剑星侧 SDK 对接映射表（StellarCraft）

> 本文档基于你机器上实机转储的 UE4SS SDK 头文件生成。
> SDK 位置：`D:\SteamLibrary\steamapps\common\StellarBlade\SB\Binaries\Win64\ue4ss\CXXHeaderDump\`
> 共 **1384 个 `.hpp`** 文件，剑星为 UE4.26，主程序 `SB-Win64-Shipping.exe`。

本文档的作用：把 `sb-mod/src/*.cpp` 骨架代码里的所有 `TODO:` 占位符，替换成**真实可用的类名、继承关系、字段偏移和函数入口**，让剑星侧 mod 可以真正编译并运行。

---

## 1. 核心类型总览

### 1.1 玩家角色（Eve）

| 项目 | 值 |
|---|---|
| 类名 | `ACH_P_EVE_01_Blueprint_C` |
| 定义文件 | `CH_P_EVE_01_Blueprint.hpp` |
| 直接基类 | `ASBCharacter` |
| 根基类 | `ACharacter`（UE4 标准角色，**自带全部 Transform / 相机接口**） |
| 相机组件 | `FollowCamera`（偏移 `0x5B8`，类型 `UCameraComponent*`）、`CameraBoom`（`0x5C0`，`USpringArmComponent*`） |
| 身体碰撞 | `BodyCollision`（偏移 `0x3910`，`UCapsuleComponent*`） |

**关键结论**：因为 `ASBCharacter` 最终继承 `ACharacter`，所以读取位置/朝向**不需要自己算偏移**，直接调用 UE4 标准接口即可：

```cpp
// 位置（Unreal 单位 = 厘米，Z 轴向上）
FVector loc = PlayerCharacter->K2_GetActorLocation();
// 朝向（控制器的旋转，单位：度）
FRotator rot = PlayerCharacter->GetControlRotation();
// 相机
UCameraComponent* cam = PlayerCharacter->FollowCamera;
FVector camLoc = cam->K2_GetComponentLocation();
FRotator camRot = cam->K2_GetComponentRotation();
```

### 1.2 敌人 / NPC（Naytiba 等）—— 已实机验证的命名规律

剑星用**前缀**区分角色类型（这是关键，直接决定 TNT 爆炸该伤谁）：

| 类前缀 | 含义 | 示例 |
|---|---|---|
| `CH_P_*` | **玩家**（Player） | `CH_P_EVE_01_Blueprint` |
| `CH_M_NA_*` | **敌人 / Naytiba**（Monster-Naytiba） | `CH_M_NA_42`、`CH_M_NA_53`、`CH_M_NA_901`（901 可能是 Boss） |
| `CH_NPC_*` | **友善 NPC**（公民/商人/老人） | `CH_NPC_CitizenMale_*`、`CH_NPC_Enya`、`CH_NPC_XionElder` |

**关键纠正**：敌人是 **`CH_M_NA_*`**，不是 `CH_NPC_*`。`CH_NPC_*` 是友善 NPC，TNT 爆炸**不应该**伤它们（除非你故意要打平民）。

**TNT 爆炸伤怪要镜像的目标**：`CH_M_NA_*` 系列：

| 类名 | 文件 |
|---|---|
| `ACH_M_NA_42_Blueprint_C` | `CH_M_NA_42_Blueprint.hpp` |
| `ACH_M_NA_53_Blueprint_C` | `CH_M_NA_53_Blueprint.hpp` |
| `ACH_M_NA_901_Blueprint_C` | `CH_M_NA_901_Blueprint.hpp` |

**所有角色类（玩家/敌人/NPC）都继承自 `ASBCharacter`**，因此统一用 `ASBCharacter*` 处理即可，用类前缀（`IsA`）区分阵营。

> 运行时过滤敌人：`Actor->IsA(ACH_M_NA_42_Blueprint_C::StaticClass())` 或遍历时匹配类名含 `CH_M_NA`。

### 1.3 血量 / 伤害数值字段（`SB.hpp` 内）

| 字段 | 偏移 | 类型 | 含义 |
|---|---|---|---|
| `MaxHP` | `0x0078` | `int32` | 最大血量 |
| `BaseDamageReductionByShield` | `0x0098` | `float` | 护盾基础减伤 |
| `DamageReductionPerShieldBock` | `0x009C` | `float` | 每格护盾减伤 |
| `SmallWeightTypeDamageAdditiveRate` | `0x00E8` | `float` | 轻体型伤害加成 |
| `LargeWeightTypeDamageAdditiveRate` | `0x00EC` | `float` | 重体型伤害加成 |
| `RangeAttackDamageAdditiveRate` | `0x00F0` | `float` | 远程伤害加成 |
| `MeleeAttackDamageReductionRate` | `0x00F4` | `float` | 近战伤害减免 |
| `WeakPointDamageAdditiveRate` | `0x002C` | `float` | 弱点伤害加成 |

> 这些字段在 `SB.hpp` 里属于不同的结构体（如 `FSB*Stat`），具体属于哪个 `ASBCharacter` 子字段需在写代码时按 `SB.hpp` 的实际类归属确认。

---

## 2. 骨架代码 → 真实 SDK 替换对照

### 2.1 `stellar_blade_mod.cpp` 的 `read_player()`

**骨架占位（TODO）：**
```cpp
// TODO: s.x = sb.location.X / kUnitsPerBlock; etc.
// TODO: s.yaw = convert(sb.rotation.Yaw); s.pitch = convert(sb.rotation.Pitch);
```

**真实实现：**
```cpp
#include <SDK/CH_P_EVE_01_Blueprint_classes.hpp>
#include <SDK/Engine_classes.hpp>

static PlayerSnapshot read_player()
{
    PlayerSnapshot s{};
    s.inGame = false;

    // 拿到世界里的第一个玩家角色
    auto* World = UObject::GWorld;  // UE4SS 提供的全局世界指针
    if (!World) return s;

    auto* PlayerChar = static_cast<ACH_P_EVE_01_Blueprint_C*>(
        World->GetFirstPlayerController()->GetCharacter());
    if (!PlayerChar) return s;

    FVector loc = PlayerChar->K2_GetActorLocation();   // cm, Z-up
    FRotator rot = PlayerChar->GetControlRotation();   // 度

    // Unreal(cm, Z-up) → MC(方块, Y-up)
    // 1 方块 = kUnitsPerBlock（协议里定义，建议 100 或与 SkyCraft 的 70 对齐）
    s.x = loc.X / kUnitsPerBlock;
    s.y = loc.Y / kUnitsPerBlock;
    s.z = loc.Z / kUnitsPerBlock;
    // 轴交换：Unreal 的 Z(上) → MC 的 Y(上)，Unreal 的 Y → MC 的 Z
    // 详见 DESIGN.md 坐标映射章节
    s.yaw   = -rot.Yaw;     // 修正方向
    s.pitch = rot.Pitch;
    s.inGame = true;
    return s;
}
```

### 2.2 `collision_exporter.cpp` 的碰撞导出

**占位要点** → 真实做法：

剑星用 UE4 的碰撞查询。导出地形给 MC，用 **射线扫/重叠查询** 采样玩家周围的静态几何：

```cpp
// 用 UE4 的 LineTrace / Sweep 采样地面高度
FHitResult Hit;
FVector Start = PlayerLoc + FVector(0,0,5000);
FVector End   = PlayerLoc - FVector(0,0,5000);
World->LineTraceSingleByChannel(Hit, Start, End, ECC_WorldStatic);
```

> 碰撞导出是剑星侧**工作量最大**的部分，需要在实机上用 `ActorDumperMod`（Ctrl+7）先 dump 场景里有哪些静态 Actor，再决定采样策略。这一块在 DESIGN.md 的 Phase 1 有详细设计。

### 2.3 `actor_mirror.cpp` 的敌人镜像

**真实入口（已确认）：**
```cpp
// ASBCharacter 上的现成函数
class ASBCharacter* GetLookAtTargetCharacter();    // 当前注视目标
class ASBCharacter* GetLockOnTargetCharacter();    // 当前锁定目标
```

镜像流程：遍历 `World->PersistentLevel->Actors`，筛出 `ASBCharacter*` 且 `IsA(ACH_NPC_*_C)` 的敌人，读其位置/血量，写入协议 Actor 表。

### 2.4 `damage_applier.cpp` 的伤害结算

**占位要点** → 真实做法：

UE4 标准角色有 `TakeDamage` 虚拟函数（在 `Engine.hpp` 里）。但剑星有自己的战斗数值系统，直接调 `TakeDamage` 可能绕过剑星的受击表现（硬直/飙血/仇恨）。

**正确路径**：通过 `ASBCharacter` 上的技能/攻击数值结构（`SB.hpp` 里 `FSB*Stat` 那些 `*DamageAdditiveRate` 字段）找到剑星自己的"施加伤害"函数入口。这一步需要实机用 **UE4SS Live Property Editor** 或 **IDA/Ghidra** 定位，是剑星侧剩余的逆向工作。

> 骨架代码里 `damage_applier.cpp` 已经写好了调用框架和伤害换算公式（`5 + 0.25×等级`），只需要把 `TODO: 调用剑星受击函数` 替换成实测到的函数指针。

---

## 3. 剩余需要实机定位的 3 个函数入口

SDK 头文件给了**类型和字段**，但**函数调用地址**（尤其是剑星自定义的战斗/伤害函数）头文件里只有声明，真正的实现地址需要实机定位：

| # | 要定位的入口 | 用途 | 定位方法 |
|---|---|---|---|
| 1 | 剑星"施加伤害"函数 | TNT 爆炸伤 Naytiba | UE4SS Live Property Editor + IDA |
| 2 | 相机矩阵读取 | 合成 MC 画面 | 已可用（`FollowCamera`） |
| 3 | 角色输入注入 | 切换控制权 | `ASBCharacter` 的输入处理函数 |

---

## 4. 下一步行动清单

1. **把 SDK 头文件复制进项目**，让 `sb-mod` 能 include：
   ```
   stellarcraft/sb-mod/src/SDK/   ← 把 CXXHeaderDump 整个拷过来
   ```
2. **替换 `read_player()` 的 TODO**（上面 2.1 已给真实代码）
3. **用 UE4SS 控制台 `dump` 命令**实机确认玩家角色类名是否就是 `ACH_P_EVE_01_Blueprint_C`
4. **定位伤害函数入口**（第 3 节表格），替换 `damage_applier.cpp` 的 TODO
5. **配 xmake/VS2022** 编译剑星侧 mod

---

*本文档是实机转储后的一手映射，会随剑星版本更新而变化。*
