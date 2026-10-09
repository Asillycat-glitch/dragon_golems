# 升级 / 坐骑 / 载客：现状与可行性

这份是"龙能不能用犬型升级、能不能当坐骑、最多坐几个"的反查结果。依据是
`libs/modulargolems-2.7.3.jar`（javap 反查，也就是 `gradle.properties` 里
`modulargolems_version = 2.7.3` 实际加载的那一份）+ `dev/xkmc/modulargolems-2.6.34-sources`（源码，**仅供对照，
数字以 2.7.3 的字节码为准**）+ Minecraft 1.20.1 Forge 源码。

> **2026 更新：第五节的 C + C′ + 飞行驾驶已经落地。** 龙现在可以被"骑乘手杖"点名、
> 载一个玩家，并用 **空格上升 / Shift 下降 / WASD 前后左右 / 鼠标决定机头朝向与俯仰**
> 在大世界里自由飞。实际做了什么、在哪几个文件里，见文末的
> [七、已经落地了什么](#七已经落地了什么)。
>
> **2026 第二刀（对应"小问题"清单）**：坐骑升级现在**能装到龙身上**了（第一节的结论已作废，
> 见 `RideUpgradeMixin`）；泰坦体型下"乘客被自己的龙挡住"也修了（子箱的 root vehicle +
> 拾取箱自动收缩，见第七节末；**座椅仍然只由模型决定**）；选定/回收补了拾取半径与
> 回收手杖瞄准辅助。数值与开关的统一说明在
> [CONFIG.md](CONFIG.md)。

## 一、升级能不能装在龙身上：只有一条被挡

本家判定"这条升级能不能装到这个傀儡"只走一条链：

```
CraftEventListeners.appendUpgrade(铁砧)        → if (!upgrade.fitsOn(holder.getEntityType())) return EMPTY;
GolemUpgradeItemHandler(升级台)                → if (!upgrade.fitsOn(holderItem.getEntityType())) return EMPTY;
UpgradeItem.fitsOn(type)                       → 遍历自己带的 modifier，任一 modifier.fitsOn(type) 为真即通过
GolemModifier.fitsOn(type)                     → 默认 true
```

2.7.3 里**只有 `RideUpgrade` 覆写 `fitsOn`**（整包扫 `fitsOn` 只命中 5 个类：定义处、两个调用处、
`UpgradeItem`、`RideUpgrade`）：

```java
// dev.xkmc.modulargolems.content.modifier.ride.RideUpgrade
public boolean fitsOn(GolemType<?, ?> type) {
    return type == GolemTypes.TYPE_DOG.get();   // 只有狗型
}
```

结论：**除了坐骑升级（`modulargolems:mount_upgrade`，内部 id `ridding_speed_up`），
本家其它升级现在就能装在龙身上**，不需要改任何东西。

> **★ 这一条现在被我们改掉了（2026 第二刀）**：坐骑升级**也能装到龙身上**了 ——
> `mixin/RideUpgradeMixin` 在 `fitsOn` 的 HEAD 处补一句"目标是龙 → 放行"
> （`require = 0`，本家改了也不会崩），开关是 TOML 里的
> `mount.mountUpgradeOnDragon`（默认开，关掉就退回下面这个原版行为）。
> 装上之后龙会带 `GolemFlags.PASSIVE`（AI 不索敌、不被 mob 盯上），
> 但**驾驶与骑手指令 R/G/V 完全不受影响** —— 那几条路都不看这个 flag。

## 二、升级槽：龙是 5，比狗（2）还多

`GolemHolder.getRemaining(mats, upgrades)` 决定"还能装几条升级"（下面是 2.7.3 的实际实现）：

```java
int base = getEntityType().getUpgradeSlots();                     // 基准槽位
for (IUpgradeItem e : upgrades) if (e.consumesSlot()) base--;     // 每条占槽的升级 -1
for (var e : GolemMaterial.collectModifiers(mats, upgrades).entrySet())
    base += e.getKey().addSlot(upgrades, e.getValue());           // add_slot 这类修饰符再加回来
return base;
```

- `GolemType.getUpgradeSlots()` 的默认实现就是 `values().length`（部件数）；本家三种各自覆写它、
  改成配置项：金属 `largeGolemSlot`（默认 4）、类人 `humanoidGolemSlot`（3）、狗 `dogGolemSlot`（2），
  都在 `MGConfig.COMMON` 里可调。
- 龙没有覆写它 → 走默认实现，`base = 部件数` = **5 槽**。
- 注意 2.7.3 里"这条升级占不占槽"是由 `IUpgradeItem.consumesSlot()` 回答的，
  不再是 `instanceof UpgradeItem`（这段判定现在只写在 `getRemaining` 一处，凡走它的路都一样）。
- 所以"龙用不上升级"不是槽位问题——龙反而比狗宽松

## 三、坐骑升级（mount upgrade）做了什么

```java
MOUNT_UPGRADE = new RideUpgrade(1,                       // 只有 1 级，占 1 个升级槽
        AttrEntry(STAT_SPEED,    () -> 0.30),            // 移速 +30%
        AttrEntry(STAT_JUMP,     () -> 0.25),            // 跳跃 +25%
        AttrEntry(STAT_HEALTH_P, () -> 0.20)             // 最大生命 +20%
);
// 另外 onRegisterFlag → GolemFlags.PASSIVE：不主动攻击；onAttacked/onDamaged 里
// "不是以它为目标的 mob"打它会被直接取消/清零
```

**它本身不提供任何载客能力。** 坐骑升级在狗身上只是"把它变成一个被动、跑得快的宠物"，
真正"能骑"是狗这个**实体类**自带的代码。所以：

> 只让龙装上坐骑升级 → 龙会变成不主动攻击、也不被 mob 攻击的被动宠物，
> 但仍然没人能骑上去，等于自废武功。要做就得连实体逻辑一起做。

## 四、载客能力真正的出处（狗）

`DogGolemEntity`（2.7.3 里方法名是 SRG）覆写了 **6 个**载客相关方法：

| 方法（SRG） | 作用 |
|---|---|
| `m_7310_` = `canAddPassenger` | 数量与宽度上限 |
| `m_19956_` = `positionRider` | 乘客沿身体排座（`width/2 - width/total*offset`，跟着 `yBodyRot` 转） |
| `m_6048_` = `getPassengersRidingOffset` | `bbHeight * 0.9 - 0.25` |
| `m_6688_` = `getControllingPassenger` | 第一个乘客是玩家或傀儡 → 由它操控（否则返回 null） |
| `m_7340_` = `onPassengerTurned` | 非驾驶座乘客强制跟随身体朝向（`clampRotation`，相对机头夹在 ±160°） |
| `m_20348_` = `addPassenger` | 上人时先 `setInSittingPose(false)`，再走父类 |

（这张表以前把 `addPassenger` 写成了 `m_7332_`；2.7.3 里被覆写的是 `m_20348_`，`m_7332_` 根本没出现。
`positionRider` 的排座偏移还有个细节：第 0 位是 0.7，后面每位再加"驾驶者是不是玩家"的 1.7 / 1.2。）

除这 6 个之外，狗还覆写了几个"驾驶"用的：`getRiddenRotation`、`executeRidersJump`、
`m_274312_` / `m_274498_`（原版玩家骑乘输入那一套），以及 `m_245547_` = `getRiddenSpeed`
（`MOVEMENT_SPEED × MGConfig.COMMON.riddenSpeedFactor`，默认 **0.8**）。

上限公式：

```java
double size = getAttributeValue(GolemTypes.GOLEM_SIZE.get());   // 绝对值，不是倍率
return count <= Math.min(size * 2 - 1, 3) && total <= getBbWidth() + 1e-3;
// count = 现有乘客 + 新乘客；total = 乘客们的 bbWidth 之和
```

而 `AbstractGolemEntity` 把 `getScale()` 覆写成 `GOLEM_SIZE / 默认值`，`LivingEntity.getDimensions`
又用 `getScale()` 缩放体积 → **size 变大时龙的体积（也就是宽度预算）一起变大**。

### 龙的情况

| | 狗的数值 | 龙的数值 |
|---|---|---|
| 默认 `GOLEM_SIZE` | **1**（`dog_golem` 注册属性时写死） | **5.0**（我们 `createAttributes` 里写的） |
| 默认体积 | 0.9 × 0.9 | **2.6 × 2.2**（`DragonGolemItems` 的 `.sized(2.6F, 2.2F)`） |
| 重铸基数 | 各部件铁砧消耗之和 | 64 × 5 = **320** |
| 现在能不能载客 | 能（自带上面那 6 个覆写），骑乘杖 / 傀儡 `checkRide` 都认狗 | **不能**：龙一个载客方法都没覆写；而且三个"上龙"入口全写死了狗 |

不过龙的 AI **已经给"玩家驾驶"让好路了**：`DragonIdleGoal.movable()`、`DragonRangedGoal.canUse()`、
`DragonDiveGoal.canUse()`、`DragonSonicGoal.canUse()` 都会在 `getControllingPassenger()` 是玩家时收手，
`DragonFollowTeleportGoal.canUse()` 也是。所以真接上载客之后，不用再改 AI 去跟玩家抢控制权
（现在这些判断是白写的 —— 龙根本不会有乘客）。

### 如果把狗的公式照搬到龙身上

龙现在的尺寸基准是 **GOLEM_SIZE = 5**（见 RENDERING.md：`getScale() = 当前值 / 默认值`，
所以基准 5 在 size 5 时倍率仍是 1）。代进狗的公式：

| 龙的 GOLEM_SIZE | 数量上限 `min(size*2-1, 3)` | 宽度预算 `2.6 × size/5` | 实际能坐谁 |
|---|---|---|---|
| 5.0（默认） | **3（开局就吃满）** | 2.6 | 3 个类人（0.6×3 = 1.8）✓；3 只狗（2.7）✗；2 金属 + 1 类人（3.4）✗ |
| 5.5（1 级 size_up） | 3 | 2.86 | 3 只狗（2.7）✓；2 金属 + 1 类人（3.4）✗ |
| 6.0（2 级 size_up，最高 2 级） | 3 | 3.12 | 2 个金属（2.8）✓；玩家 + 2 金属（3.4）✗；3 个金属（4.2）✗ |

所以基准取 5 之后，**数量上限不再是瓶颈，宽度变成真正的闸门**：一个傀儡算一个乘客，
玩家 0.6 宽、类人傀儡 0.6、狗 0.9、金属 1.4（都按各自 `sized()` × 各自 `getScale()` 算）。
数量上的硬上限仍然是公式里写死的 **3**。

### 怎么把宽度预算做到 4.2（只改龙的数据）

宽度这条链是：

```
龙的 getBbWidth()  =  EntityType.sized(width)  ×  龙的 getScale()
龙的 getScale()    =  GOLEM_SIZE / 本类注册的默认 GOLEM_SIZE     （再乘重铸系数）
```

两个乘数都是我们自己的数据，所以有两条路：

| 做法 | 改动 | 结果 | 评价 |
|---|---|---|---|
| A. 加宽判定箱 | `DragonGolemItems` 里 `.sized(2.6F, 2.2F)` → `.sized(4.3F, 2.2F)` | `4.3 × (5/5) = 4.3` ≥ 4.2 ✓ | **一行数据就够**，但碰撞箱真的变胖（水平要占约 5 格） |
| B. 靠倍率放大 | 需要 `getScale() ≥ 4.2 / 2.6 ≈ 1.62` → `GOLEM_SIZE ≥ 8.08` | 体型升级最高 2 级只到 6.0（1.2×、3.12 宽） | **走不通**，除非改材料加 `max_size`（那会把整条龙放大 1.62 倍，模型和判定箱一起） |

> 现状提醒：`DragonGolemItems` 里 `ENTITY` 的**注释**是照 4.3 写的，但代码里的
> `.sized(...)` 还是 **2.6F** —— 也就是说这一档宽度预算**还没真的落地**。

判定箱是"X/Z 同宽"的方柱（`EntityDimensions` 只有宽和高、没有长），所以"把龙做长"帮不上忙，
只能加宽。4.2 是数学上的刚好（比较里还有 `+1e-3` 容差，三只 1.4 宽的刚好过），
留 0.1 余量取 **4.3** 更稳。

加了宽度之后，能装下的组合（数量上限仍为 3）：

| 组合 | 宽度和 | 4.3 够不够 |
|---|---|---|
| 3 金属傀儡 | 4.2 | ✓ |
| 2 金属 + 1 类人 | 3.4 | ✓ |
| 3 类人 | 1.8 | ✓ |
| 玩家 + 2 金属 | 3.4 | ✓ |
| 3 个"带 1 级体型升级"的金属（金属基准 3 → 3.5，倍率 3.5/3 ≈ 1.17，每个宽 ≈ 1.63） | ≈ 4.9 | ✗（要 4.9 以上） |

> 如果不想让碰撞箱变胖：**别用本家的"宽度和"公式**，在 `DragonGolemEntity` 里自己写
> `canAddPassenger`（比如固定 3 座，或 `min(1 + (size - 5) * 2, 3)` 按 size 算座位），
> 这样判定箱保持 2.6 也能坐 3 个。这条不算"改数据"，但只动龙自己的代码，同样不碰别的傀儡。

> 如果想让"升级 → 多坐人"这条成长线保留下来，就不能直接用 MG 的公式（基准 5 时它开局就满档），
> 得换成以 5 为基数的版本，例如 `min(1 + (size - 5) * 2, 3)`（5→1 个、5.5→2 个、6→3 个），
> 或者干脆固定 3 个、让 size_up 只负责变大。

注意"乘客"是混着算的：1 个玩家 + 2 个傀儡就满了；`getControllingPassenger` 只看第一个乘客，
所以谁先上去谁驾驶。

> **落地时没有照搬这两条**（见第七节）：龙自己的 `canAddPassenger` 不是"数量 + 宽度"公式，
> 而 `getControllingPassenger()` 也不是"只看 0 号位"—— 它扫一遍乘客、**有玩家就归玩家开**，
> 所以"傀儡先坐上去、玩家后上来"时驾驶权仍然归玩家（原版会把玩家插到 0 号位）。

## 五、想落地的话，三种做法

| 方案 | 改哪里 | 代价 |
|---|---|---|
| A. 让本家那条坐骑升级对龙生效 | mixin `RideUpgrade#fitsOn`（`@Inject(HEAD, cancellable)`，判断 `type == DragonGolemItems.TYPE.get()`） | ✅ **已落地**：`mixin/RideUpgradeMixin`（开关 `mount.mountUpgradeOnDragon`）。好处是玩家看到的就是本家那条升级物品 |
| B. 自己做一条"龙用坐骑升级" | 继承本家 `RideUpgrade` 覆写 `fitsOn` + 用 `SimpleUpgradeItem` 做物品，注册进本家 `GolemTypes.MODIFIERS`（写法照军械库 `GolemUpgrades`，那边有 20 多个例子） | 不用 mixin，但要额外多一条升级物品；且**必须配合 C** 才有意义 |
| C. 让龙真能载客 | `DragonGolemEntity` 搬入上面那 6 个方法（`canAddPassenger` / `positionRider` / `getPassengersRidingOffset` / `getControllingPassenger` / `onPassengerTurned` / `addPassenger`；公式里的 3 可以按龙的定位再定） | 纯我们自己的代码，不需要 mixin |
| C′. 再加"上龙"入口 | ✅ **两条都落地了**。**玩家**那条：`DragonRideHandler`（`PlayerInteractEvent.EntityInteract`，`rider_wand` 与 `omnipotent_wand_rider` 两种手杖都认，**不开 mixin**）。**傀儡乘客**那条：`GolemCheckRideMixin`（注入本家 `MetalGolemEntity` / `HumanoidGolemEntity` 的 `checkRide` HEAD，只在目标是龙时接管；本家那两个覆写只认狗 / 马）。见下文「上龙入口」与「傀儡乘客（炮台）」 | 不再需要上游 mgdp |

飞行坐骑还多一层：玩家要**驾驶**的话得写 `getRiddenInput` / `getRiddenSpeed` / 跳跃那套
（狗是地面版：`getRiddenSpeed` 用移速 × `MGConfig.riddenSpeedFactor`，`executeRidersJump` 给竖直速度），
龙的版本要按飞行写（或者干脆只当"运兵车"，玩家骑上去但龙自己飞）。

## 七、已经落地了什么

### 飞行驾驶（`DragonRiderControl`）

| 操作 | 效果 |
|---|---|
| **空格** | 上升 |
| **左 Ctrl** | 下降（贴到地面就停住，不会钻进地形；头顶有方块也不会硬顶） |
| **W / A / S / D** | 沿<b>机头朝向</b>前后飞 + 左右平移（`player.zza` / `player.xxa`，联机同样有效） |
| **鼠标** | 左右 = 机头转过去（6°/tick 起，按转弯半径放大，不会瞬间掉头）；上下 = 机体俯仰（±35°），模型和子碰撞箱一起低头/抬头 |

> **下降键为什么是左 Ctrl 而不是 Shift**：原版 `Player.wantsToStopRiding()` 直接返回
> `isShiftKeyDown()`，而它在 `Player.baseTick` 里一旦为真就 `stopRiding()` ——
> **Shift 是"下车键"**，拿它做下降只会让人从龙背上掉下去（第一版就是这么写的）。
> 这个方法在 `Player` 上是 `protected` 且不是 `Entity`/`LivingEntity` 的方法，**龙覆写不了**
> （试过，编译不过），所以下降键只能另绑一个客户端自己发。
> 又因为 Ctrl 这类键服务端根本看不见，所以升降状态走自定义包
> （`DragonRideInputPacket`），而不是读玩家身上的状态。

要点：

- **接的是原版骑乘管线，不是绕开它**。1.20.1 的流程是
  `LivingEntity.travel` → `travelRidden`（只在 `getControllingPassenger()` 是玩家时走）
  → `tickRidden` / `getRiddenInput` / `getRiddenSpeed` → `move`，全都在 `aiStep` 里的
  `super.aiStep()` 那一步执行。所以龙只回答"往哪飞、多快"，碰撞/贴墙/贴地全给原版。
  **反过来做（在 `aiStep` 里直接写 `deltaMovement`）是行不通的**：`travelRidden` 紧接着会用
  `moveRelative(getRiddenInput × getRiddenSpeed)` 重写一遍，前面写的会被覆盖成 0。
  （悬停/俯冲能直接写速度，是因为那条路上没有玩家操控、走的是另一条分支。）
- **唯一写在 `super.aiStep()` 之后的是"升降"**：原版骑乘不给竖直输入，而 `noGravity` 下没人写
  Y 速度就永远是 0，所以空格/Ctrl 那一笔在移动结算完之后补 Y，和悬停是同一个套路。
- **竖直速度的三个来源必须互斥**：骑手 / 技能接管（`diveVelocity`）/ 悬停（`applyHover`）。
  悬停分支会按"回到目标高度"覆盖 Y，所以**有骑手时那两个分支都不能走** ——
  否则就是"按空格反而被压回待机高度 3 格"（这个 bug 真机确认过）。
- **服务端权威**。原版只在 `isControlledByLocalInstance()` 为真时才算骑乘输入，而这个方法在
  服务端默认返回 false（它不是客户端、也没有 `isEffectiveAi`），所以龙覆写了它
  （有玩家操控时返回 true）。不覆写的话单机看着正常、联机时所有操作都会被服务端退回。
- **驾驶期间不打人**：`setTarget` 拒收、掷出来的技能作废、俯冲姿态清零 —— 玩家在操作时被自己龙的
  技能抢走控制权会很难受。**但玩家自己按的技能是例外**，见下面"骑手技能键"。
- 按键状态的读取源是 **`LocalPlayer.input`**（`Input.jumping` / `Input.shiftKeyDown`）优先，
  `KeyMapping.isDown()` 兜底 —— 前者才是原版真正在用的输入，装了按键重绑类 mod
  （比如 Controlling）时后者可能不一致。
- 龙覆写了 `isMovable()`：`hasDriverSeatOccupied()`（玩家或傀儡在座）为真就一律视为可动。
  **不能只看 `getControllingPassenger()`** —— 傀儡不在其中，那会让"龙先落进停止模式、
  再让傀儡上背"这条最自然的路径上 `isMovable()` 恒为 false，三个攻击 goal 全部 `canUse()=false`
  （真机表现就是"傀儡坐在龙背上，龙却完全不袭击"）。

### 上龙入口

- 龙覆写了 `canWandModify`（龙没有配置卡那一套，直接放行），于是**骑乘手杖**的判定链
  `ConfigCard.getFilter(user).test(golem) && golem.canWandModify(user)` 能过。
- 但手杖里真正"骑上去"的那一步写死了 `DogGolemEntity`
  （`if (golem instanceof DogGolemEntity e) user.startRiding(e, false);`），龙走到那里只会
  `return true` —— 手杖判定"成功"、人却没上去。所以加了 `DragonRideHandler`
  （Forge 的 `PlayerInteractEvent.EntityInteract`，它在实体交互之前派发），
  **不改别人的代码、不开 mixin**。
- **必须同时认两种手杖**：普通骑乘手杖 `modulargolems:rider_wand`，以及万能手杖切到"骑乘"模式后的
  `modulargolems:omnipotent_wand_rider`（l2itemselector 切模式 = 换物品，是另一个注册项）。
  早先只认路径里含 `rider_wand` 的那一个，于是万能手杖这条路整条漏掉：我们的 handler 不管，
  本家的 `ride` 又只认狗 —— 真机表现就是"右键龙完全没反应"，而装上上游 mgdp
  （`RiderWandItemMixin` 注入 `RiderWandItem.interactLivingEntity` 的返回处，对任何非犬型
  `AbstractGolemEntity` 直接 `startRiding`）就正常。**这条入口现在完全由我们自己负责，不需要 mgdp。**
- **不要求"龙停着"**：`startRidingFrom` 原先有一道 `isParked()` 闸门，真机反馈同样不好用
  （"跟在身边的龙右键没反应"）。本家的狗和上游 mgdp 都没有这道限制，已去掉。
- **也没有别的状态门槛**：在飞、在俯冲/冲锋、在战斗、开着什么模式，都能上；点身体、头颈、
  尾巴、翅膀都算（`PlayerInteractEvent.EntityInteract` 是 `Player#interactOn` 最前面发的，
  那时还没走到 `DragonGolemPartEntity#interact` 的"转发给本体"，所以 handler 里先自己拆一层
  `PartEntity#getParent()`，否则就是"点身体能骑、点翅膀没反应"）。
- **座位一条硬规则**：一条龙上只能有**一个玩家**（见 `canAddPassenger`）。除此之外不再拦人 ——
  背上已经驮着傀儡（炮台乘客）时玩家照样能上，**不用先把傀儡请下去**：原版
  `Entity#addPassenger` 在服务端会把玩家**插到 0 号位**（`list.add(0, player)`，条件正是
  "首个乘客不是玩家"），原本坐那儿的傀儡整体后移一格，座位重排由 `positionRider` 每 tick 跟着算
  （座位号走 `seatIndexOf`：玩家在前、傀儡在后，不赌乘客列表的顺序）。另外"刚下过车 3 秒内
  上不去"是**原版**的 `boardingCooldown`（`Entity#canRide`，60 tick），全游戏载具共用，没有动。
- **`getControllingPassenger()` 按"乘客里有没有玩家"回答**（而不是"0 号位是不是玩家"），
  免得列表顺序一旦错位就退化成"没人驾驶、按键全无反应"。
- 座位：`positionRider` 沿机体轴向排座（驾驶座在前、傀儡乘客依次向后），换算和龙头/龙嘴同一套
  （`rotateAndLift`），所以龙俯仰时整排座位跟着一起转。

### 傀儡乘客（炮台）

龙背上除了驾驶座还能坐傀儡（`MAX_GOLEM_PASSENGERS = 3`）。**怎么让傀儡上去**：拿装好傀儡的
**成品（holder）右键龙** —— 本家的 `GolemHolder.interactLivingEntity` 会在龙的位置召唤它，
并对它调 `checkRide(龙)`。

> ✅ 这条路原先只有上游 mgdp 的 `GolemRideMixin` 补着（它在本家
> `MetalGolemEntity` / `HumanoidGolemEntity` 的 `checkRide(LivingEntity)` HEAD 处 `ci.cancel()`，
> 然后**无条件** `startRiding(任何目标)`）。现在换成我们自己的 `GolemCheckRideMixin`，
> **只在目标是龙时**接管，坐不下（座位满了）就不 cancel、交回本家原逻辑；狗和马照旧走本家。
> 上背动作统一走 `DragonGolemEntity.rideAsPassenger`，座位规则由 `canAddPassenger` 把关。

- **傀儡不会顶掉驾驶权**：`getControllingPassenger()` 只在首个乘客是 `Player` 时返回非 null。
  傀儡刻意不算 —— 它没有输入源（不像本家狗那样能读 `AbstractGolemEntity` 的意图），
  算成驾驶者会让 `travelRidden` 整条管线空转、龙反而动不了。
- **货舱模式**：背上有傀儡且没有玩家驾驶时，龙悬停原地、不再随机游走
  （傀儡在背上射击时龙自己绕圈会让射手永远瞄不稳）。它仍然跟随主人、照常索敌开火。
  **注意不能靠 `setIdleSettled` 实现** —— 那个字段的正常管理者是 `DragonIdleGoal`，
  它每 tick 都会重算并覆盖，别处置位活不过一 tick；所以改成在 `aiStep` 开头每 tick 维护。
- **目标共享接口 `DragonTargetSource`**：`dragonCurrentTarget()`（顺序：命令手杖写的
  `forcedTarget` → 本家目标槽）/ `dragonForceTarget(t)`（走本家 `setTargetRaw`，
  绕开 `PASSIVE` 限制）。给背上的傀儡用 —— **龙负责索敌、傀儡负责开火**。
  抽成顶层接口的原因：嵌进 `DragonGolemEntity` 会形成"接口引用外层泛型参数"的循环继承，
  javac 直接报 `cyclic inheritance`。
- **未实测**：本家傀儡的攻击 goal 在它作为乘客时是否仍然运行（`AbstractGolemEntity.tick()`
  里没有任何"是不是乘客"的检查，看起来会跑；但 vanilla 对乘客的移动类 AI 有特殊处理）。
  这是"傀儡能不能在龙背上开火"的前提。

### 骑手技能键（R / G / V）

| 键 | 技能 | 冷却 |
|---|---|---|
| **R** | 冲锋（俯冲） | 30 秒 |
| **G** | 龙息锥 | 3 秒 |
| **V** | 龙弹；身体是幽匿的龙换成音爆 | 3 秒 |

三个键都能在"控制"里改，且**只在骑着龙时响应**（平时不占键）。

**为什么要加一个自定义网络包。** 原版只把"跳跃"和"潜行"两个按键状态同步到服务端
（升降键用的就是这两个），R/G/V 在服务端根本看不见。所以：客户端按 → 发包 → 服务端执行。
链路是 `DragonRiderKeys`（按键 + 算瞄准点）→ `DragonSkillPacket` → `DragonNetwork`（通道）
→ `DragonGolemEntity.onRiderCommand`（校验）→ `DragonRiderSkillGoal`（执行）。
包体里带**客户端算好的瞄准点**，因为服务端拿不到玩家的准星；
服务端会重新校验（是不是真骑着、序号合法、冷却、距离），**不信任客户端**。

**瞄准点怎么算：** 先 `level.clip` 打方块截断射线，再在射线上找实体，都没中就取射线终点。
注意**不能**用 `player.pick(range, ...)`——那个方法内部用玩家属性 `forge:entity_reach`
（默认 3 格）当实体距离闸门，传 48 进去也只打得到 3 格内的东西。

**"骑手指令"和 `PASSIVE` 的关系（这里有个绕不开的矛盾）。**
装坐骑升级的龙带 `GolemFlags.PASSIVE`，本家 `canAttackType` 返回 `!PASSIVE`，而它同时被
"能不能打"和"能不能被当敌人"复用 —— 于是被动龙的 `setTarget` 全被挡、`getTarget()` 永远是 null，
AI 那套"有目标才开火"在驾驶时完全用不上；可没有目标就没有任何伤害管线。

解法是给龙加一个**只在执行骑手指令期间为真**的开关 `riderCombat`：

```java
canAttackType(type) → riderCombat || super.canAttackType(type)
canBeSeenAsEnemy()  → riderCombat || super.canBeSeenAsEnemy()
```

于是装了坐骑升级的龙：**平时依旧完全被动**（不索敌、不被当敌人，和升级说明一致），
**只有玩家按键的那几秒**才真有攻击性，指令一结束立刻收回。

**为什么单独写一个 `DragonRiderSkillGoal`，不复用 AI 那三个 goal：**
① AI 的重心是"追着索敌到的目标打"，而这里根本没有目标；② AI 的冷却挂在 `pendingSkill` /
`diveCooldown` 上、和"这一轮抽中了谁"绑死，玩家按键不该去搅那套调度
（否则按一下 G 会把 AI 的大招 CD 也吃掉一颗）；③ 混在一起就得处处判断
"这个目标是玩家给的还是索敌来的"，很容易写出"骑着龙它还自己俯冲"这类 bug。
共用的只有表现与结算那几层（`breathDamage` / `applyBreathEffects` / `mouthPosition`），
以及**冲锋直接复用 `DragonDiveGoal`**——那套航线 + 撞击结算是实测过的，重写只会引入手感差异
（骑手按下 R 时由 `orderDive()` 手动把 DIVE 排进调度，因为驾驶期间掷骰子那条路被乘客挡住了）。

### 体型与乘客：泰坦升级为什么曾经"挡住"骑手（2026 第二刀，已修）

**症状**：普通体型骑着没事，一装第三方那种 +300% 体型的"泰坦"类升级（`getScale() = 4`），
坐在龙上的人就**什么都点不了** —— 右键交互、左键攻击、放方块全部被自己的龙吃掉。

**机理**（对着 1.20.1 / Forge 47.4.20 的源码核过）：Forge 的多部件实体是**独立实体**，
而 `Entity.getRootVehicle()` 是沿"我是不是乘客"往上爬的 —— 子箱自己永远不是乘客，
所以它返回**它自己**，和本体不是同一辆"车"。原版 `ProjectileUtil.getEntityHitResult`
（准星选中）里有两支，其中

```java
if (aabb.contains(射线起点)) { 无条件选中它，距离 = 0 }    // ← 不看 root vehicle
```

这一支会把"眼睛落在箱子里的那个实体"直接抓成准星目标。普通体型下人的眼睛刚好在躯干箱顶面
上面一点（约 1.6 格眼高 vs 2.36 格箱顶），泰坦时箱顶 ×4 = 9.44 格、眼高不变 → 眼睛被埋进箱子，
于是准星锁死在离自己 0 格的箱子上。**本家的犬坐骑永远不会犯**：它没有子碰撞箱，
整只狗就是一个实体，原版那套规则天然生效。

**修法三条**：

| 改动 | 位置 | 作用 |
|---|---|---|
| 子箱如实回答自己属于本体 | `DragonGolemPartEntity.getRootVehicle()` → `parent.getRootVehicle()` | "擦到箱子"那一支会正确跳过自己的坐骑（准星不会再被自己的龙头/躯干抢走），骑手也不会打到自己的龙 |
| **座椅锚在模型表面、下沉量固定为世界格** | `DragonGolemEntity` 的 `RIDER_SURFACE_UP_PX` / `RIDER_SINK` / `RIDER_FORWARD_PX` | 旧写法"模型像素 × 体型"会让<b>下沉量跟着体型放大</b>：1 倍沉 0.85 格（腿藏进背里，看着就是坐在背上），泰坦 4 倍沉 3.4 格 → 只有 1.8 格高的人被整个埋进龙身体里。现在锚在背脊表面（躯干方块顶面 = 模型 y 4）再减固定 0.85 格，1 倍数值与旧公式**严格相等**，大体型也永远只陷同样深度 |
| 拾取箱自动收缩（保险） | `DragonGolemEntity.pickRadiusFor` + 本体/子箱的 `getPickRadius()` | 任何水平罩着乘客的箱子，拾取半径被削到"乘客眼高 − 箱顶"为止（碰撞体积与伤害判定不受影响）。座椅锚到表面之后这条基本不再触发，留作兜底 |

> 座椅的**前后**与**座位间距**仍然按模型像素 × 体型走（大体型时座位自然铺开在更宽的背上），
> 只有**竖向**改成了"表面 + 固定下沉"。这两件事都不要再退回"整座标量 × 体型"。

### 选定与回收（2026 第二刀）

- `Entity.getPickRadius()` 原版默认 0 = 选中范围就是碰撞箱。现在本体 +0.5、
  子箱 +1.0（`selection.bodyPickRadius` / `selection.pickRadius`）。
  实际生效值会被 `pickRadiusFor` 自动削一次：**罩着乘客的那块不许把乘客的眼睛包进拾取箱**，
  否则骑手的准星会被自己的坐骑吃掉。本体刻意小，就是给这个收缩留余量。
- **回收手杖**：本家 `RetrievalWandItem` 的射线谓词写死 `e instanceof AbstractGolemEntity`，
  我们的子箱是 `PartEntity`，**被整个过滤掉** —— 看着龙头/尾巴/翅膀按下去什么都点不到。
  新增 `DragonRetrieveHandler`（`PlayerInteractEvent.RightClickItem`）：自己打一条认子箱的射线，
  命中后调用**本家自己的** `ItemStack#interactLivingEntity`（过滤/权限/收进背包全是本家逻辑）。
  本家自己选得中、或者玩家潜行（= 回收周围全部）时一概不抢。
- 顺带更正一条旧说法：**回收手杖的射程从来不是 3 格**。`forge:entity_reach` 只管原版
  攻击 / `player.pick`；手杖走的是自家 64 格射线（`MGConfig.COMMON.retrieveDistance`）。

### 已知遗留

0. **★ 玩家冲锋（R）骑着时失效**（2026-10 实测，尚未修复；AI 自发的俯冲正常，勿动）。
   现象：骑着按 R → goal 启动、姿态会变（`dive` 1→7）、冷却照扣，但**龙的位置一动不动**；
   **玩家一下龙就立刻能冲出去**。真机日志的分界线极其干净：每次撞击结算时都是 `rider=false`。
   最可能的根因：`isControlledByLocalInstance()` 在有玩家驾驶时返回 true（当初为修联机），
   于是骑乘期间位置由客户端模拟，而客户端跑的是 `applyHover()`（悬停）；
   `diveVelocity` 只是服务端 goal 里的普通 Java 字段（不是 `entityData`、也没有 `@SerialClass`），
   **客户端的悬停位置压过了服务端的俯冲速度**。姿态能变而位置不能变，正因为姿态走 `entityData`（有同步）。
   修复方向：冲锋期间让服务端接管位置（`isRiderOrderedDive()` 时 `isControlledByLocalInstance()` 返回 false），
   并注意确认不引起画面抖动/回弹。
   **这条 bug 一共五层叠加，前四层已修**（分别是：goal 从未被给过目标 / `canUse`+`canContinueToUse`
   的"有乘客别起飞"守卫把骑手指令也挡了 / `tickBody`+`tickVertical` 每 tick 抹掉速度姿态目标 /
   冲锋标志依赖会被 `stop()` 自己清掉的 `pendingSkill` / 回位传送把冲锋中的龙拽回来）。
   第五层修完后现象才从"完全没动静"变成"骑着不动、下龙立刻动"，从而暴露出本层。
   **任何一层单独修都看不到效果**，所以排查时每修一层都像"没变化" —— 这是这条 bug 花了很多轮的原因。
1. **子碰撞箱整体偏高约 2.4 格**（这是"手杖难指"的根因，尚未改动）。
   实测换算（1 倍体型）：躯干箱中心在 `y + 2.86`，而**可见的龙身中心渲染在 `y + 0.42`**
   —— 也就是玩家看着龙身瞄准时，准星其实穿在判定箱<b>下方</b>的空气里。原因在
   `DragonGolemEntity.rotateAndLift` 的输入是"模型像素 + 两次 lift 平移"，而整套子箱的
   `*_UP_PX` 常量（躯干 16、头/颈 20、尾 10、翼 5）是按`NECK_BASE_Y_PX = 20` 这条**动画基准**
   抄的，不是按模型实际渲染高度量出来的。
   想修的话改这几个常量即可（躯干从 16 往 4 那一档调、头/颈从 20 往 12 那一档调），
   **推荐先在游戏里按 F3 + B 打开判定箱显示，对着模型读数再定值**
   （判定箱和模型必须同时看得见才能一次调准）。
   > **2026 第二刀的缓解**：这一条本身没动（没有可靠的理论值，只能实机量），但"点不中"
   > 的体感已经由两件事补上了 —— 子箱拾取半径 `selection.pickRadius`（默认 1.0 格，
   > 会把可点区域整体外扩）、以及回收手杖的认子箱瞄准辅助
   > （`DragonRetrieveHandler`）。详见 [CONFIG.md](CONFIG.md) 第五节。
2. **手杖的射程**：原版选中生物的距离上限是玩家属性 `forge:entity_reach`（默认 **3.0 格**），
   而龙待机时离地 3 格、子箱又偏高，站在地面上就够不着。第 1 条修完之后这一条会自然缓解；
   真想再放宽得动玩家属性（会影响到所有生物，不建议）。
3. 龙现在不会因为上人就自动降落：待机的龙在离地 3 格（会随体型 × √体型）。想让它"停在人面前"
   得另做一条"被主人靠近就降高度"的逻辑。
4. **本节这些只能靠实机验证**：开发环境的 `runClient` 跑不起来（l2library 自己的 mixin，
   见 [DEV-RUN.md](DEV-RUN.md)），所以改动只做到了"对着 1.20.1 的 Forge 类逐个核对签名 + 编译通过"。
   实机第一次要重点看四件事：上得去、动得了、升降跟手、**下龙以后龙能自己回到悬停高度**
   （`applyHover` 会把 `noGravity` 重新管起来；如果下龙后它原地悬着不掉，就是这个衔接没接好）。

