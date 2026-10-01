# 升级 / 坐骑 / 载客：现状与可行性

这份是"龙能不能用犬型升级、能不能当坐骑、最多坐几个"的反查结果。依据是
`libs/modulargolems-2.7.3.jar`（javap 反查，也就是 `gradle.properties` 里
`modulargolems_version = 2.7.3` 实际加载的那一份）+ `dev/xkmc/modulargolems-2.6.34-sources`（源码，**仅供对照，
数字以 2.7.3 的字节码为准**）+ Minecraft 1.20.1 Forge 源码。

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
| 重铸基数 | 各部件铁砧消耗之和 | 8 + 12 + 12 + 8 + 8 = **48** |
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

## 五、想落地的话，三种做法

| 方案 | 改哪里 | 代价 |
|---|---|---|
| A. 让本家那条坐骑升级对龙生效 | mixin `RideUpgrade#fitsOn`（`@Inject(HEAD, cancellable)`，判断 `type == DragonGolemItems.TYPE.get()`） | 本仓库要开 mixin 基础设施（`dragom_golems.mixins.json` + build.gradle 的 `MixinConfigs`；`mixin.env.remapRefMap` 已经有了）。好处是玩家看到的就是本家那条升级物品 |
| B. 自己做一条"龙用坐骑升级" | 继承本家 `RideUpgrade` 覆写 `fitsOn` + 用 `SimpleUpgradeItem` 做物品，注册进本家 `GolemTypes.MODIFIERS`（写法照军械库 `GolemUpgrades`，那边有 20 多个例子） | 不用 mixin，但要额外多一条升级物品；且**必须配合 C** 才有意义 |
| C. 让龙真能载客 | `DragonGolemEntity` 搬入上面那 6 个方法（`canAddPassenger` / `positionRider` / `getPassengersRidingOffset` / `getControllingPassenger` / `onPassengerTurned` / `addPassenger`；公式里的 3 可以按龙的定位再定） | 纯我们自己的代码，不需要 mixin |
| C′. 再加"上龙"入口 | 最简单：我们自己加一根骑乘道具（`user.startRiding(dragon)`）；想复用本家的骑乘杖/傀儡自动上坐骑，就得 mixin `RiderWandItem.ride`（私有静态，只对 `DogGolemEntity` 执行 startRiding；由 `m_6880_` = `interactLivingEntity` 调）、`HumanoidGolemEntity.checkRide`（认狗和马）、`MetalGolemEntity.checkRide`（只认狗，而且要求狗比它宽） | 不加就永远是"能载但没人上得去" |

飞行坐骑还多一层：玩家要**驾驶**的话得写 `getRiddenInput` / `getRiddenSpeed` / 跳跃那套
（狗是地面版：`getRiddenSpeed` 用移速 × `MGConfig.riddenSpeedFactor`，`executeRidersJump` 给竖直速度），
龙的版本要按飞行写（或者干脆只当"运兵车"，玩家骑上去但龙自己飞）。

## 六、待定

1. 龙到底要"玩家驾驶"还是"只运傀儡"？
2. 数量上限要不要照抄 `min(size*2-1, 3)`，还是固定 3 / 按龙体型另定？
3. 坐骑升级走 A（改本家行为）还是 B（自带一条）？
