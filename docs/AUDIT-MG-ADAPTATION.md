# 龙 × 傀儡装配：适配审计

> 审计对象：本仓库 `src/main/java/a_silly_cat/dragon_golems/**`（龙的全部 Java 代码）
> 与 **Modular Golems 2.7.3**（`libs/modulargolems-2.7.3.jar`，另用
> `dev/xkmc/modulargolems-2.6.34-sources` 交叉对照）。
>
> 结论先说：**龙是「一个自定义 GolemType + 自己的一套战斗 AI」**，不是把
> 大型/类人/犬型三种傀儡的适配逐条继承下来。凡是本家以
> `instanceof`/`== GolemTypes.TYPE_*` 写死的入口，龙都**没有自动获得**；
> 凡是本家走 `GolemType` / `GolemHolder` / `GolemModifier` / `GolemMaterial`
> 的通用链，龙**基本都吃到了**。下面按用户点名的六个维度拆。

## 0. 读过的范围

| 侧 | 读到的内容 |
|---|---|
| 我们 | `dragon` 包核心 Java：`DragonGolemEntity`（3885 行）、`DragonGolemType`、`DragonGolemPartType`、`DragonGolemItems`、`DragonGolemEquipHandler`、`DragonGolemMenuControl`、全部战斗/移动 goal（Dive / Ranged / Sonic / RiderSkill / Idle / FollowTeleport）、`DragonPathFollower`、`DragonFlightAssist`、`DragonCavePosition`、`DragonTargetSource`、`DragonGolemFireball`、配置与 mixin；纯渲染几何（`DragonGolemModel` 等）按方法/接口核对，未逐顶点阅读 |
| 本家 | `GolemType`、`GolemType` 的三个子类、`AbstractGolemEntity`、`Metal/Humanoid/DogGolemEntity`、`SweepGolemEntity`、`GolemMenuControl` 及三个实现、`EquipmentsMenu/Container/Screen`、`GolemHolder`、`GolemEquipUtil`、`GolemPart`、`GolemMaterial`、`GolemPartConfig`、`GolemStatType`、`GolemModifier`、`GolemMeleeGoal`、`Golem3DTargetGoal/TargetManager`、`GolemModes/PatrolMode`、`TableBlock/GolemUpgradeMenu/GolemUpgradeItemHandler`、`GolemAssembleRecipe/GolemReplaceRecipe/GolemSmithAddSlotRecipe`、`GolemEventListeners`、`GolemAttackListener`、`WeaponAttackListener`、`DogGolemRenderer/MetalGolemRenderer/HumanoidGolemRenderer`、`GolemBannerLayer`、数据包（`recipes/`、`tags/`） |

> 2.7.3 的源码不是随 jar 发布的。本次用 ForgeFlower 反编译到
> `build/_mg273src/`（build 目录，不参与版本库）；2.6.34 的源码在
> `dev/xkmc/modulargolems-2.6.34-sources/`。两者不一致的地方以 2.7.3
> 反编译结果为准。

## 1. 攻击挂载：**自定义嘴巴技能接上了，武器/装备挂载整条没接**

### 1.1 本家怎么挂

本家的「攻击」不是一个统一接口，而是四层：

| 层 | 本家实现 | 作用 |
|---|---|---|
| 近战 | `GolemMeleeGoal`（`AbstractGolemEntity.meleeGoal`，由 `GolemWeaponManager` 挂上） | 追到 `ENTITY_REACH` 内，`mob.doHurtTarget`，走 `m_7327_` |
| 武器 | `SweepGolemEntity.weaponManager` + `GolemWeaponRegistry`（`HUMANOID`/`LARGE` 有 `initBasic()`） | 根据主手/副手/箭矢/备用武器挂弓、弩、枪、枪械 goal |
| 升级/材料技能 | `GolemModifier.onRegisterGoals` → 各 `*AttackGoal` | 炽焰喷射、死光、音波炮、火球雨…… |
| 伤害事件 | `ModifierEventListeners` + `GolemAttackListener` + l2damagetracker 的 `AttackCache` | 药水、穿甲、吸血、击杀特效、目标加成等 |

也就是说：**「攻击挂载」在本家里 = 武器槽 + goal 选择器 + 真实伤害源**。
升级本身没有 `onAttack`/`onHit` 回调（`GolemModifier` 里确实没有函数式参数），
所以「怎么打出去」决定了一半升级是否生效。

### 1.2 龙接了什么

| 本家的东西 | 龙的状态 |
|---|---|
| `SweepGolemEntity` 的 `performRangedDamage` / `performDamageTarget` / `canSweep` | ✅ 继承；俯冲撞击走这条，带 `golem_sweep` 横扫 |
| `GolemModifier` 的伤害事件（药水、穿甲、吸血、击杀、目标加成） | ✅ 技能伤害统一走 `DragonGolemEntity.dealSkillDamage`（`target.hurt`，攻击者=龙） |
| `l2damagetracker` 的 `CreateSourceEvent` | ✅ 龙息/龙弹用 `breathSource(...)` 手动触发；❌ 音爆没走（见下） |
| 主手/副手/箭矢/备用/肩部武器 | ❌ 龙的 `WEAPONS` 是空注册表（`DragonGolemEntity.java:115`），菜单也没有这些槽 |
| `GolemMeleeGoal` | ❌ 被 `stripNativeGoals()` 主动摘掉（`DragonGolemEntity.java:2821-2828`） |
| `GolemModifier.onRegisterGoals` 挂上来的 `*AttackGoal` | ❌ 默认整批摘掉（`nativeSkillGoals=false`，`DragonGolemEntity.java:2818-2827`） |
| 近战跳劈族（`EarthquakeHelper` 的 `performJump`） | ⚠️ 只把**俯冲撞击**接到 `performEarthQuake`，没有接 `performJump`（`DragonGolemEntity.java:3147-3160`） |
| 鞍/乘客共享目标 | ⚠️ `DragonTargetSource` 只有定义和实现，**没有任何地方调用**（全仓库搜索只命中接口和实体自己） |

龙自己的四个攻击入口是：

- `DragonDiveGoal`：俯冲掠过，撞击走 `diveImpact` → `performRangedDamage`；
- `DragonRangedGoal`：嘴部龙息锥 / 龙弹；
- `DragonSonicGoal`：幽匿身体的线状音爆；
- `DragonRiderSkillGoal`：骑手 R/G/V。

出手点统一是 `DragonGolemEntity.mouthPosition()`（`DragonGolemEntity.java:2892-2901`），
这层是自洽的；问题在**本家武器/装备/技能 goal 的挂载点没有被改造**。

### 1.3 缺口（按严重度）

#### P0：幽匿身体的 `SonicAttackGoal` 会被自己反复摘掉

`updateAttributes()` 里，当身体是幽匿时会给龙补挂本家的 `SonicModifier`：

- `DragonGolemEntity.java:2746-2758`：`sonic.onRegisterGoals(...)` 把 `SonicAttackGoal` 加进 goalSelector 和 `sonicGoals`；
- `DragonGolemEntity.java:2815-2854`：`stripNativeGoals()` 每 20 tick 执行一次，条件是
  `goal instanceof SonicAttackGoal` → 把它移除；
- 也就是说它最多活 20 tick，然后被自己的「清理外来远程 goal」逻辑删掉。

结果：本家 `sonic_boom` modifier 的 tooltip/属性在，但那个 goal 实际上不工作
（最多活约 20 tick）。我们自己的 `DragonSonicGoal` 仍然能打音爆，
所以**不是没有音爆，而是本家这条挂载是死的**。
修法：`stripNativeGoals()` 跳过 `sonicGoals` 里的实例，或者干脆不要补挂本家那条、只保留我们自己的。

#### P1：武器/装备攻击整条链没有

- `DragonGolemEntity.WEAPONS = new GolemWeaponRegistry<>()` 从未 `initBasic()`；
- `DragonGolemMenuControl` 只有两个「狗式」槽，没有 `MAINHAND`/`OFFHAND`/`BACKUP`/`ARROW`/肩部；
- `GolemEquipUtil.applyItemOnHolder` 只有 `TYPE_GOLEM`/`TYPE_DOG`/`TYPE_HUMANOID` 三个分支，龙落到 `return ItemStack.EMPTY`（`build/_mg273src/.../GolemEquipUtil.java:62-97`）；
- `GolemEventListeners.onEquip` 同样只有这三个分支（`GolemEventListeners.java:38-99`）。

所以：本家武器（金属巨剑、机械弓、音波炮……）和装备提供的
`IGolemModifierItem`/`IGolemEquipmentItem` 攻击/防御 modifier，在龙身上**没有入口**。
`getModifiersExtended()` 本身是通的，但龙没有可装备的物品。

#### P1：goal 族技能升级默认是死的，打开开关也不完全对

`nativeSkillGoals=false` 时，`isForeignRangedGoal()` 会把
`BaseRangedAttackGoal`/`SonicAttackGoal` 以及 `dev.xkmc.modulargolems.*AttackGoal`
全摘掉（`DragonGolemEntity.java:2845-2854`）。这些 goal 是按「站在地上的傀儡」写的：

- 出手点在自身坐标（对龙是身体中心，不是嘴）；
- 射界固定，不随体型缩放；
- 伤害走原版 `hurt`，会把目标重新顶进无敌帧。

打开 `skills.nativeSkillGoals` 只是「原样放回来」，没有做「从嘴发射 + 射程按体型」的改造。
所以这类升级对龙最多是「能放，但位置/射程/无敌帧都不对」。

#### P1：`DragonSonicGoal` 的伤害源绕过了 `CreateSourceEvent`

`DragonSonicGoal.echoSource()` 直接 `new DamageSource(registry.getHolderOrThrow(MGDamageTypes.ECHO), dragon)`
（`DragonSonicGoal.java:290-299`）。这不会触发 l2damagetracker 的
`CreateSourceEvent`，于是「穿甲 / 破魔 / 改伤害源」这一类升级对音爆无效。
龙息/龙弹走的是 `breathSource()`（`DragonGolemEntity.java:3082-3097`），是正确的。

#### P1：乘客共享目标接口没有接线

`DragonTargetSource` 的设计是「龙发现谁、背上的傀儡打谁」，文档也这么写；
但全仓库没有任何代码在傀儡乘客侧查询它。`DragonGolemEntity` 只是实现了接口，
`DragonTargetSource.java` 的示例代码没有落地。所以「龙负责索敌、傀儡负责开火」目前
只是**龙单方面有目标**，乘客不会自动同步。

#### P2：近战跳劈族只接了一半

`triggerMeleeUpgradesOnDive()` 调 `performEarthQuake`，没有调 `performJump`。
地震族（巨兽、猫灾、Mowzie 等）能触发；跳劈族（Ignis 等 `performJump` 实现）不会。
如果「俯冲 = 龙的近战」这个设定成立，建议明确：跳劈族是要「俯冲时起跳」还是**故意不支持**。

#### P2：没有「目标丢失/打不到就换目标」的机制

本家 `GolemMeleeGoal.checkAndPerformAttack` 有 `getTargetResetTime` + `targetResetNoMovementRange`
（`build/_mg273src/.../GolemMeleeGoal.java:248-267`）：长时间打不到会 `resetTarget(null)`。
龙把近战 goal 摘了，自己的 `DragonRangedGoal`/`DragonSonicGoal` 只有 `CLOSE_MAX=200`
的「这一轮放弃」，目标本身不清；下一 tick `canUse` 又会重新为同一个目标启动。
目标活着但不可达时，会无限追。

### 1.4 幽匿音波里「到底哪条链」在生效

「幽匿音波」在代码里其实是**三条不同的东西**，粒子/伤害/挂载各不相同：

| 名称 | 触发来源 | 粒子 | 伤害 | 现状 |
|---|---|---|---|---|
| **MG `SonicModifier` / `SonicAttackGoal`** | 身体是幽匿 → `updateAttributes` 把 `modulargolems:sonic_boom` 补进 modifier 表并挂 goal | 硬编码 `ParticleTypes.SONIC_BOOM`（`SonicAttackGoal` 里） | `10 × lv`，走 `mobAttack`，16 格线状 | **被 `stripNativeGoals` 每 20 tick 摘掉**；这是一条独立于我们技能的本家自动音波 |
| **AI `DragonSonicGoal`** | `pickSkill` 抽中 `SONIC`，且 `isSonicBody()` 为真 | 前摇用 `dragon.breathParticle()`（**数据包**，幽匿 = `minecraft:sonic_boom`）；光束硬编码 `ParticleTypes.SONIC_BOOM` | `modulargolems:echo_attack`，**直接 `new DamageSource`，不走 `CreateSourceEvent`**；线状穿透 | 我们自己真正的 AI 音波；伤害/长度/冷却都正常，只有「改伤害源」类升级和光束粒子没接数据包 |
| **骑手 V `DragonRiderSkillGoal.fireSonic`** | 玩家按 V，且 `isSonicBody()` 为真 | 光束硬编码 `ParticleTypes.SONIC_BOOM` | `dragon_sonic` 伤害类型（`breathDamage`，吃护甲/附魔、不吃抗性；目标原本无敌帧） | 只打线上最近一个；3 秒 CD；粒子同样没走数据包 |

所以：

- **如果你说的是「龙息/残云」的粒子**：已经是数据包驱动了——
  `DragonBodyEntry.breathParticle` / `cloudParticle`（[DragonBodyEntry.java:64-110](src/main/java/a_silly_cat/dragon_golems/content/config/DragonBodyEntry.java#L64-L110)），
  由 `DragonGolemEntity.breathParticle()` / `breathCloudParticle()` 统一出口
  （[DragonGolemEntity.java:3189-3202](src/main/java/a_silly_cat/dragon_golems/dragon/DragonGolemEntity.java#L3189-L3202)）。
  龙息前摇/喷流/落点、龙弹残云、骑手 G/V 的龙息版都读它；支持 `minecraft:dust{...}` 这种带参数粒子。
- **如果你说的是「音波光束」的粒子**：还没走数据包。两处 `DragonSonicGoal.java:240` 与
  `DragonRiderSkillGoal.java:238` 都是硬编码 `ParticleTypes.SONIC_BOOM`；`SonicAttackGoal` 也是硬编码。
  想做到「数据包/接口决定粒子效果」，最自然的是给 `DragonBodyEntry` 加一个
  `sonicBeamParticle`（默认 `minecraft:sonic_boom`）并在这两处读它；如果还想换音效，
  再加 `sonicSound`。这样幽匿/其它材料都能用同一个主题表控制音波表现。
- **`echoSource` 的问题和粒子无关**：它是「伤害源没有经过 l2damagetracker 的
  `CreateSourceEvent`」，所以穿甲/破魔这类**改源**升级对 AI 音波无效。修法是让它也走
  `breathSource(...)` 那条路，而不是直接 `new DamageSource`。

> 结论：数据包驱动粒子的架构已经有一半（龙息/龙弹全通），**音波光束是唯一还没接进来的硬编码点**；
> 如果你本来计划「所有技能粒子都走数据包/接口」，那现在只差音波这一格。

## 2. AI 行为：**移动/索敌大体自洽，但巡逻、过滤、丢目标、乘客共享有缺**
### 2.1 本家怎么组织

`AbstractGolemEntity.m_8099_()` 挂的基础 goal（2.7.3）：

| 优先级 | goal |
|---|---|
| 0 | `GolemFloatGoal` |
| 1 | `TeleportToOwnerGoal` |
| 4 | `FollowOwnerGoal` |
| 7 | `LookAtPlayerGoal` |
| 8 | `GolemRandomStrollGoal` |
| 9 | `RandomLookAroundGoal` |
| target 1/3/6 | `HurtByTargetGoal` / `Golem3DTargetGoal` / `ResetUniversalAngerTargetGoal` |

加上：

- `GolemWeaponManager` 挂近战/远程；
- `GolemModifier.onRegisterGoals` 挂技能；
- 三种子类各自补 `GolemMeleeGoal` / `GolemRiddenMeleeGoal` 等。

索敌不是「最近的就打」：`Golem3DTargetGoal` 用 `TargetManager.predicateTarget`
评分，`predicateTarget` 会看 **强制目标、最后攻击者、主人/队长、配置卡的目标过滤器、
`DefaultFilterCard` 的默认敌我判定**（`TargetManager.java:43-84`）。

模式（`GolemModes`）：FOLLOW / GUARD / STAND / FREE_WANDER / SQUAD / ROUTE。
`PatrolMode.tick()` 负责推进 ROUTE 的路径点，而它**只从 `FollowOwnerGoal` 里被调用**
（`FollowOwnerGoal.java:44-46, 86`）。

### 2.2 龙接了什么

龙保留的基础 goal：`GolemFloatGoal`、`LookAtPlayerGoal`、`RandomLookAroundGoal`、
`HurtByTargetGoal`、`Golem3DTargetGoal`、`ResetUniversalAngerTargetGoal`。

龙替换/新增的：

| 优先级 | goal | 说明 |
|---|---|---|
| 1 | `DragonFollowTeleportGoal` | 替换本家 `TeleportToOwnerGoal`，水平/垂直分开判定，回位不打断技能 |
| 4 | `DragonIdleGoal` | 替换 `FollowOwnerGoal` + `GolemRandomStrollGoal`，自己算游走/回位/悬停 |
| 2 | `DragonDiveGoal` | 俯冲 |
| 3 | `DragonRangedGoal` | 龙息/龙弹 |
| 2 | `DragonSonicGoal` | 音爆（仅幽匿身体） |
| 2 | `DragonRiderSkillGoal` | 骑手 R/G/V |

龙主动摘掉：`GolemMeleeGoal`、`TeleportToOwnerGoal`、`FollowOwnerGoal`、
`GolemRandomStrollGoal`、以及默认的外来远程 `*AttackGoal`（`DragonGolemEntity.java:2815-2854`）。

### 2.3 缺口

#### P0：ROUTE（巡逻）永远不会推进路径点

链路：

1. `getTargetPos()` 在 ROUTE 模式下返回 `PathConfig` 的第 `getPatrolStage()` 个点
   （`AbstractGolemEntity.java:1212-1234`）；
2. 推进 stage 的唯一入口是 `PatrolMode.tick()` → `advancePatrolStage()`；
3. `PatrolMode.tick()` 只在 `FollowOwnerGoal` 里被调用；
4. 龙把 `FollowOwnerGoal` 摘了，`DragonIdleGoal` 里没有任何 `getMode().tick(this)`。

ROUTE 在本家里是「**按记录点巡逻**」：用 `PathRecordCard` 录一串坐标，`CommandWandItem`
负责把路线画出来，`getTargetPos()` 每次返回当前 `patrolStage` 指向的点；
`FollowOwnerGoal` 负责飞到/走到那个点，`PatrolMode.tick()` 在导航结束、且脚在地上/水里时
调用 `advancePatrolStage()` 切下一个点。到最后一个点再回绕到第 0 个。

龙的问题在于它**不用导航**：`DragonIdleGoal` 只把 `getTargetPos()` 当成「随机游走的软中心」，
既不飞向精确航点，也没有任何 `advancePatrolStage()`；而 `PatrolMode.tick()` 自己又要求
`navigation.isDone()` + `onGround()/isInWater()`，对一直悬停的龙本来就不成立。
所以龙切到 ROUTE 后，stage 永远是 0：它会围着第一个点转，不会去第二个点；
`CommandWandItem` 画出的整条路线仍然会显示，但龙不会走完。

修法不能只补一句 `getMode().tick(this)`（龙永远不满足 `onGround` / `navigation.isDone`），
要么在 `DragonIdleGoal` 里对 ROUTE 做专门分支：

```java
if (dragon.getMode() == GolemModes.ROUTE) {
    Vec3 wp = dragon.getTargetPos();
    // 飞向 wp（不要随机采样），到 wp 附近直接 advancePatrolStage()
    if (horizontalDistance(dragon, wp) < ARRIVE) dragon.advancePatrolStage();
}
```

要么单独写一个 `DragonPatrolGoal`，把「到点 → 换下一个点」做成飞行版；
两种都比复用 `PatrolMode.tick()` 更合适。

#### P0/P1：32 格主动扫描绕过了本家的目标过滤器

`DragonGolemEntity.aiStep()` 里每 20 tick 有一次「巡场」：

```java
level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(32),
    m -> m.isAlive() && !m.isSpectator()
        && !(m instanceof Player)
        && !this.isAlliedTo(m)
        && this.canAttack(m));
```

（`DragonGolemEntity.java:2094-2120`）

它只用了 `canAttack`（`canAttackType` + `isAlliedTo`），**没有用 `predicateTarget`**。
后果：

- 配置卡的目标过滤器（`GolemConfigEntry.targetFilter`）不生效；
- `modulargolems:friendly` 标签/默认敌我判定不生效；
- 命令手杖写的 `forcedTarget` 可能被这次扫描覆盖（扫描只在 `getTarget()==null` 时跑，
  但 `forcedTarget` 本身不保证 `getTarget()` 非空）；
- 没有排除自己的乘客（`hasPassenger`/`isPassengerOfSameVehicle`），
  理论上可能锁到自己背上不同 owner 的傀儡；
- 永远排除玩家，而本家配置过滤器允许「对玩家敌对」时是能打玩家的。

建议：直接用 `this.predicateTarget(m)` 替代 `canAttack(m)`，并补乘客/同车排除；
或者干脆删掉这次扫描，只靠 `Golem3DTargetGoal`（它有 FOLLOW_RANGE 35 和完整过滤）。

> 同类问题还有 `DragonCavePosition.scanThreats()`（`DragonCavePosition.java:181-188`）：
> 它也用 `canAttack` 找「威胁」，会把配置卡里设置为友方的单位算成需要躲开的敌人。

#### P2：AI 龙的「无条件常驻穿墙」——**已修（0.4 洞穴模式收尾）**

审计时 `DragonGolemEntity.aiStep()` 末尾有一段测试开关：

```java
if (this.getControllingPassenger() == null) {
    this.noPhysics = true;
}
```

它会把 `DragonFlightAssist` 按 `freeFlight / rescue / noRoom / stickyGhost` 算出来的结果
**整个覆盖掉**：没有玩家骑的龙永远 noPhysics，`cave.noClip` 等配置全部失效，
脱困、避障、寻路绕障在 AI 身上变成死代码 —— 也是「明明能穿墙却不认路」的来源之一。

现在改成：

- 只有 `cave.ghost = true` **且** `inCave()` 判定成立、且没有骑手时才主动幽灵（`isCaveGhostActive()`）；
- 幽灵时移动层明确走直线：`DragonPathFollower` 直接返回目标、`DragonFlightAssist` 不再避障，
  并且**不再把「身体嵌在方块里」当卡住**（不触发脱困/把龙往外推）；
- 离开洞穴或关掉 `cave.ghost` 后，一切交回飞行辅助的自动脱困 / 常驻幽灵；
- 开阔地里 A* 重新可用，但直飞路径干净时不会跑 A*，另有单 tick 预算与节点上限。

#### P1：没有本家近战 goal 的「目标重置」保护

见 1.3 P2。龙的目标一旦锁上，只会在目标死亡/传送/`DragonFollowTeleportGoal.landAt`
时被清；没有「打不到一段时间就放弃」的逻辑。

#### P1：乘客共享目标没接线

见 1.3 P1。`DragonTargetSource` 目前是死接口。

#### P2：模式参数没有对齐本家配置

龙的自定义移动使用自己的常量：

- 跟随/回位半径 24/12（`DragonIdleGoal`）；
- 传送阈值 48/24（`DragonFollowTeleportGoal`）；
- 巡游速度/回位速度 0.45/0.70；
- 骑乘速度 0.20（`DragonRiderControl`）。

本家的 `MGConfig.startFollowRadius`、`stopWanderRadius`、`maxWanderRadius`、
`riddenSpeedFactor` 等对龙**不生效**。这不一定是 bug（龙是飞行单位，参数本来就不同），
但整合包作者改本家配置时，会发现龙不跟着变。至少应该在文档里写明「龙不使用这组键」。

#### P2：`isMovable()` 与「停留」语义的冲突

龙覆写了 `isMovable()`：只要 `hasDriverSeatOccupied()`（玩家或傀儡乘客）为真就返回 true
（`DragonGolemEntity.java:1005-1021`）。这解决了「傀儡上背后龙不攻击」的问题，但副作用是：

- 切到 STAND（停止）且背上有傀儡时，龙仍然可移动/可攻击；
- 本家「停止 = 放下、别动手」的语义在龙 + 炮台的组合上不成立。

这是有意的设计取舍（代码注释也承认），但需要和「停止」的产品语义对齐。

#### P2：残留的 `LookAtPlayerGoal`/`RandomLookAroundGoal`

这两个还在 goalSelector 里。龙的 `faceMovement()` 每 tick 把 yRot/yBodyRot/yHeadRot
钉到运动方向，实际会盖掉它们；但它们仍会占 LOOK 通道、做无效计算。不是功能缺口，
属于「没有清理干净」。

## 3. 护甲：**属性链在，装备护甲整条没有**

### 3.1 本家怎么算护甲

本家没有单独的 `getArmorValue()`；护甲完全走原版：

```
Attributes.ARMOR / ARMOR_TOUGHNESS
  ├─ 材料 stats：GolemTypes.STAT_ARMOR（Attributes.ARMOR, Kind.ADD, HEALTH 过滤）
  ├─ 装备：MetalGolemArmorItem / DogGolemArmorItem 的 AttributeModifier
  │        （LivingEntityMixin 把 IGolemEquipmentItem.getGolemModifiers 接到 collectEquipmentChanges）
  └─ 升级：GolemModifiers.ARMOR（+10/级，最多 2 级）、TOUGH
```

渲染也分三条：

- 金属：`GolemEquipmentRenderer`（`MetalGolemRenderer` 构造里 `addLayer`）；
- 类人：原版 `HumanoidArmorLayer` + `ElytraLayer` + `CustomHeadLayer`；
- 犬型：`DogArmorRenderer`（胸甲模型 + 染色 overlay）。

### 3.2 龙接了什么

- `DragonGolemEntity.createAttributes()` 没有显式写 `Attributes.ARMOR`，但
  `Mob.createMobAttributes()` 自带 ARMOR/ARMOR_TOUGHNESS（0 基础值）；材料属性
  通过 `GolemMaterial.addAttributes` 写入；
- `parts/dragon.json` 的 HEALTH 过滤：身体 1.0、双翼 0.6、尾巴 0.4、头/腿 0，
  所以如果某个材料有 `armor` 数值，身体会吃满、翅膀/尾巴打折；
- `armor_up` / `toughness_up` 升级（`modulargolems:diamond` / `netherite`）是
  普通 `AttributeGolemModifier`，龙能装、能生效；
- 受伤计算、`hurtArmor`（`m_6472_`）都是继承的。

**结论：龙的「材料护甲 / 升级护甲」是通的，但基础材料（铜金铁下界合金幽匿）
本身没有 armor 数值，所以裸装龙 ≈ 0 护甲。**

### 3.3 缺口

#### P1：没有任何龙用护甲物品

`DragonGolemItems` 只注册了胚料、五个部件、成品、龙弹（`DragonGolemItems.java:88-131`），
没有 `DogGolemArmorItem`/`MetalGolemArmorItem`/`IGolemEquipmentItem` 的龙版本，
也没有对应的合成/铁砧/切石机配方。`tough_item` / `golem_damageable` 标签里也没有龙装备。

#### P1：装备槽的事件判定只认旗帜

`DragonGolemEquipHandler` 只做了一件事：

```java
if (stack.getItem() instanceof BannerItem) event.setSlot(1, EquipmentSlot.HEAD);
```

（`DragonGolemEquipHandler.java:28-35`）

它没有：

- `IGolemEquipmentItem` 分支（`item.isFor(dragon.getType())`）；
- 原版 `LivingEntity.getEquipmentSlotForItem(stack)` + `stack.canEquip(slot, dragon)` 分支；
- 对 `EquipmentSlot.CHEST/LEGS/FEET` 的映射。

结果：即使你注册了一件龙用护甲，只要它不走 `IGolemEquipmentItem`，
装备界面也放不进去；走 `IGolemEquipmentItem` 的话，右键 `mobInteractImpl` 能穿，
但界面仍然不认。

#### P1：`GolemEquipUtil` 在「成品 → 装备」路径上没有龙

`GolemEquipUtil.applyItemOnHolder` 的类型分支只覆盖
`TYPE_GOLEM`/`TYPE_DOG`/`TYPE_HUMANOID`，龙落到 `return ItemStack.EMPTY`
（`build/_mg273src/.../GolemEquipUtil.java:62-97`）。

受影响的路径：

- `GolemHolder.toEntityWithItem`（`GolemHolder.java:330-341`；2.7.3 内部没有调用它，
  但它是公开入口，第三方/后续代码可能用）；
- Create 自动化的 `CreateRecipeEvents`（`.../create/automation/CreateRecipeEvents.java:26`）；
- 任何「在成品上装装备」的第三方调用。

**不影响**右键实体装备：`AbstractGolemEntity.mobInteractImpl` 自己会处理
`IGolemEquipmentItem`（`AbstractGolemEntity.java:307-336`），只要物品 `isFor` 龙。

#### P1：没有护甲渲染层

`DragonGolemRenderer` 构造只调了 `super(...)`（`DragonGolemRenderer.java:50-52`），
没有 `addLayer(new GolemEquipmentRenderer(...))`、没有 `DogArmorRenderer`、
没有 `HumanoidArmorLayer`、也没有 `GolemBannerLayer`。

所以：

- 即使你把护甲塞进 CHEST 槽，模型上**看不到**；
- 旗帜能放进 HEAD 槽（`DragonGolemEquipHandler`），但**也看不到**——
  `GolemBannerLayer` 需要模型实现 `IHeadedModel.translateToHead`，而
  `DragonGolemModel` 只实现了 `IGolemModel`（`DragonGolemModel.java:44-45`）。

#### P2：界面底图是「狗」

`DragonGolemScreenControl` 画的是 `altas_helmet` + `slotbg_dog_armor`
（`DragonGolemScreenControl.java:24-33`），`DragonGolemOverlayControl` 同样
（`DragonGolemOverlayControl.java:24-29`）。如果龙以后有自己的护甲，需要新底图；
现在这两个槽在视觉上就是「狗的狗的狗」。

### 3.4 如果要做「真护甲」：三条路线

| 路线 | 改什么 | 代价 | 适合 |
|---|---|---|---|
| **A. 保留犬式 2 槽，做龙专用头甲/胸甲** | 新 `DragonGolemArmorItem`（`GolemEquipmentItem` + `IGolemEquipmentItem`，`isFor(TYPE)`，HEAD/CHEST）、`DragonGolemEquipHandler` 加分支、一层龙甲渲染（可先做简单模型 overlay）、配方/标签 | 要做两个物品和一套模型；最贴近现有 UI | 有美术资源、想走「狗甲」那种轻量路线 |
| **B. 改成 4 件套** | `DragonGolemMenuControl.getSlotDefinition()` 用 `EquipmentsMenu.SLOTS` 或自定义 4 槽；`fillMenu` 加 HEAD/CHEST/LEGS/FEET；`DragonGolemEquipHandler` 用 `LivingEntity.getEquipmentSlotForItem` + `canEquip`；完整龙甲模型 | UI、模型、配方都更大；`EquipmentsContainer` 的 `index < 6` 假设还能用，但龙甲模型要自己画 | 想沿用原版护甲生态（铁/钻/下界合金盔甲直接穿） |
| **C. 不做真护甲，只承认 `armor_up`/材料护甲** | 删掉 CHEST 死槽（或明确只保留 HEAD 旗帜），依靠 `armor_up`/`toughness_up` 提供数值；不接渲染 | 最省；但「护甲槽」就只是装饰 | 近期不想投入模型/美术 |

不管走 A 还是 B，**数值层是现成的**：`GolemEquipmentItem` 的 AttributeModifier 会经
`LivingEntityMixin` 的 `getGolemModifiers` 自动进 `Attributes.ARMOR`/`ARMOR_TOUGHNESS`；
`getModifiersExtended()`、`hurtArmor`、装备掉落也都是通用的。真正要新写的只有
「物品 + 槽位入口 + 渲染」。

## 4. 护甲槽：**只抄了犬型的两个槽，且第二个是死的**

### 4.1 本家的槽位对比

| 种类 | `getSlotDefinition()` | 实际能放 |
|---|---|---|
| 金属 | `EquipmentsMenu.SLOTS` = MAINHAND/OFFHAND/HEAD/CHEST/LEGS/FEET | 6 + 肩部 2（`MetalGolemMenuControl.fillMenu`） |
| 类人 | 同上 6 | 6 + 皮肤/女仆模型按钮；原版护甲、鞘翅、手持物 |
| 犬型 | `DOG_SLOTS` = HEAD/CHEST | 2；CHEST 放 `DogGolemArmorItem`，HEAD 放旗帜 |

### 4.2 龙

`DragonGolemMenuControl`：

```java
public class DragonGolemMenuControl extends SweepGolemMenuControl<DragonGolemEntity> {
    public void fillMenu() {
        this.menu.addSlot("chest", stack -> isValid(EquipmentSlot.HEAD, stack));
        this.menu.addSlot("legs",  stack -> isValid(EquipmentSlot.CHEST, stack));
    }
    public EquipmentSlot[] getSlotDefinition() { return EquipmentsMenu.DOG_SLOTS; }
}
```

（`DragonGolemMenuControl.java:18-34`）

也就是**槽名/底图/索引都照犬型，但只映射了 HEAD/CHEST 两项**。

### 4.3 缺口

#### P0：第二个槽（`legs`，实际是 CHEST）没有任何物品能进去

`getSlotForItem` 的来源只有 `GolemEquipItemEvent`；`DragonGolemEquipHandler`
只 `setSlot(HEAD)`。没有任何事件给 `CHEST`，所以：

- `chest` 槽（HEAD）只能放旗帜；
- `legs` 槽（CHEST）永远 `isValid=false`，连原版胸甲都放不进去；
- 护甲槽在 UI 上存在，但功能上是**一个半**。

#### P0：`SweepGolemMenuControl.handleQuickMove` 的箭头逻辑会越界

`DragonGolemMenuControl` 继承的是 `SweepGolemMenuControl`，而它的
`handleQuickMove` 写死了 8 槽布局的索引 43/44：

```java
if (stack.getItem() instanceof ArrowItem) {
    this.menu.moveItemStackTo(stack, 43, 44, false);
}
```

（`build/_mg273src/.../SweepGolemMenuControl.java:24-27`）

龙的菜单只有 36 个玩家背包槽 + 2 个装备槽（最大索引 37）。
在龙的装备界面里 Shift 点击箭矢，会走到不存在的 43/44 槽——
这是从犬型菜单抄结构时多带的父类，Dog 自己继承的是 `GolemMenuControl`，
不是 `SweepGolemMenuControl`。建议改成和 Dog 一样直接继承 `GolemMenuControl`，
或覆写 `handleQuickMove`。

#### P1：没有 LEGS/FEET/主手/副手/肩部

如果龙要走「四件套护甲」，需要把 `getSlotDefinition` 扩到 `EquipmentsMenu.SLOTS`
（或自定义 4 格），并在 `fillMenu` 里加槽、在 `getSlotForItem` 里映射；
`EquipmentsContainer.getWrapper` 目前对 `index < 6` 用 `equipmentSlots[index]`，
扩到 6 以内是安全的，超过 6 要改容器。

#### P2：没有龙专用的槽底图/提示

`DragonGolemScreenControl` 直接复用狗的 `altas_helmet`/`slotbg_dog_armor`。
如果龙用龙甲，应该有自己的图标；否则玩家会以为「龙能穿狗甲」。

## 5. 升级槽：**5 格本身是通的，扩充配方已补，「有效升级」仍缺**

### 5.1 本家怎么算

`GolemHolder.getRemaining(mats, upgrades)`：

```java
int base = getEntityType().getUpgradeSlots();
for (IUpgradeItem e : upgrades) if (e.consumesSlot()) base--;
for (modifier : collectModifiers(...)) base += modifier.addSlot(upgrades, lv);
return base;
```

（`GolemHolder.java:699-715`）

`GolemType.getUpgradeSlots()` 默认 `values().length`；三个子类各自读配置：
金属 `largeGolemSlot`（默认 4）、类人 `humanoidGolemSlot`（默认 3）、犬 `dogGolemSlot`（默认 2）
（`MetalGolemType.java` / `HumanoidGolemType.java` / `DogGolemType.java`）。

### 5.2 龙

- `DragonGolemType.getUpgradeSlots()` 读 `DragonGolemConfig.upgradeSlots()`，默认 **5**
  （`DragonGolemType.java:36-39`，`DragonGolemConfig.java:209-211`，默认定义在 `:541-544`）；
- `getRemaining`、`GolemUpgradeItemHandler.appendUpgrade`、`CraftEventListeners.appendUpgrade`
  都是通用链，龙能正常加减升级；
- 本家唯一覆写 `fitsOn` 的 `RideUpgrade` 由 `RideUpgradeMixin` 放行
  （`RideUpgradeMixin.java:37-45`）。

**结论：5 格升级槽能用，工作台的升级页也能正常读写；问题不在「槽位数量」。**

### 5.3 现状与缺口

#### 已补：钻石/下界合金扩充模板（2026-10-24）

本家的槽位扩充走**锻造台**：

- `GolemSmithAddSlotRecipe`（`build/_mg273src/.../GolemSmithAddSlotRecipe.java:30-66`）；
- 数据包为三种傀儡各配了一条 `diamond_expansion_template_*_golem_holder.json` /
  `netherite_expansion_template_*_golem_holder.json`（`build/_mg273src/data/modulargolems/recipes/`）。

龙原先只有 `assemble_holder` 和 5 条切石机配方，**没有**
`diamond_expansion_template_dragon_golem_holder` / `netherite_expansion_template_dragon_golem_holder`，
所以这两个模板放不进龙的锻造台，5 格永远不能扩。

本次已补：

- `data/dragon_golems/recipes/dragon_golem/diamond_expansion_template_dragon_golem_holder.json`
- `data/dragon_golems/recipes/dragon_golem/netherite_expansion_template_dragon_golem_holder.json`

两条都是 `modulargolems:golem_add_slot`，`base` = `dragon_golems:dragon_golem_holder`，
`template` = 对应模板。`addition` 没有照本家写死铁锭，而是给了
`forge:ingots` 标签 + 四种内置身体材料 + `modulargolems:sculk_materials`：
`GolemSmithAddSlotRecipe.canCraftInGrid` 会先拿 `GolemHolder.getCraftMaterial(holder)` 校验
**身体材料的铸造物**，所以只有"手里这块锭确实对应这条龙的身体材料"才会通过；
给标签只是为了让铜/金/铁/下界合金以及第三方 ingot 材料都能匹配到，不是放宽成任意材料。
`echo_shard` 走 `modulargolems:sculk_materials`。第三方非 ingot 材料（例如某些 composite 材料）
仍然需要自己补进 `addition` 列表或相应的 item tag。

#### P1：很多升级对龙是「装着但不生效/半生效」

| 升级族 | 龙的状态 |
|---|---|
| `AttributeGolemModifier`（伤害、护甲、韧性、生命、速度、体型、回复） | ✅ 生效 |
| `PotionAttackModifier`（缓慢/虚弱/凋零） | ✅ 走 `LivingHurtEvent`，只要 `dealSkillDamage` 打出真实伤害 |
| `TargetBonusModifier`（绿宝石等） | ✅ 同上 |
| `AddSlotModifier` | ✅ `getRemaining` 认 |
| `SimpleFlagModifier`（防火/导电/漂浮/游泳/回收/透视…） | ✅/⚠️ flag 本身生效；`FLOAT`/`SWIM` 的移动逻辑对飞行龙意义有限 |
| `EarthquakeHelper` 地震族 | ✅ 俯冲触发（`skills.diveTriggersMelee`） |
| `EarthquakeHelper` 跳劈族 | ❌ 只接了地震，没接 `performJump` |
| goal 族（炽焰喷射/死光/音波炮…） | ❌ 默认摘掉；打开也不改出手点/射界/无敌帧 |
| 武器/装备提供的 modifier（`IGolemModifierItem`） | ❌ 龙没有武器/护甲槽可装 |
| `SonicModifier` | ⚠️ 补挂的 `SonicAttackGoal` 被 `stripNativeGoals` 每 20 tick 摘掉 |

所以「升级槽 5 个」是够的，但**能装不等于有用**：
如果只做武器/近战向的升级，龙会吃掉槽位却没有对应攻击方式。

#### P2：`GolemUpgradeItemHandler` 的「不可移除」/分页没有龙特判

这部分是通用的，不需要改；列在这里只是说明「升级台本身没有龙特有的坏点」。

## 6. 装配台 / 装配链：**装配和升级台是通的；替换部件配方缺（扩槽已补）**

### 6.1 本家的装配链

| 环节 | 本家做法 |
|---|---|
| 部件 | 切石机 / 铸造 / 序列组装（Create/Tinkers compat），再铁砧铸材料 |
| 成品 | `GolemAssembleRecipe`（`modulargolems:golem_assemble`，形状配方） |
| 换部件 | `GolemReplaceRecipe`（`modulargolems:golem_replace_part`），每种傀儡每个部件一条 |
| 装升级 | `GolemUpgradeMenu` / `GolemUpgradeItemHandler`（`golem_workbench` 方块，即「傀儡装配台」） |
| 扩槽 | `GolemSmithAddSlotRecipe`（锻造台，`golem_add_slot`） |
| 拆解 | `GolemDisintegrateMenu`（按 `GolemSlot` 通用映射） |
| 修复/回收 | 铁砧 `CraftEventListeners.fixGolem`、砂轮去升级 |
| JEI | `GolemAssemblyExtension` / `GolemReplaceExtension` / `GolemAddSlotExtension` |

### 6.2 龙现在有什么

| 环节 | 龙的状态 |
|---|---|
| 部件 | ✅ 5 条切石机配方（`data/dragon_golems/recipes/*_from_template_stonecutting.json`） |
| 成品 | ✅ `assemble_holder.json`，`W / HBT / L`，扫描顺序 W→H→B→T→L 与枚举顺序一致 |
| 换部件 | ❌ **没有 `golem_replace_part` 配方** |
| 工作台升级 | ✅ `GolemUpgradeMenu` 通用；5 格；`RideUpgrade` 由 mixin 放行 |
| 扩槽 | ✅ **已补钻石/下界合金两条**（`recipes/dragon_golem/*_expansion_template_dragon_golem_holder.json`） |
| 拆解 | ✅ 2.7.3 的 `PartSlot` 是按 `GolemTypes.TYPES` 遍历 + `IGolemPart.getSlot()` 建映射的（javap 确认），龙的 5 个 `GolemSlot` 能对上 |
| 铁砧升级/修复 | ✅ 通用；`getHealingMaterial` 取身体材料 |
| JEI | ✅ 本家白送装配/铁砧页；我们的 `DragonGolemsJeiPlugin` 补说明页 |

### 6.3 缺口

#### P0：5 条 `golem_replace_part` 配方缺失

本家每种傀儡都有：

- `metal_golem/replace_body.json`、`replace_left_arm.json`、`replace_right_arm.json`、`replace_legs.json`；
- `humanoid_golem/replace_body.json`、`replace_arms.json`、`replace_legs.json`；
- `dog_golem/replace_body.json`、`replace_legs.json`。

龙一个都没有。结果：

- 已经合成的龙不能「换一个部件」——只能拆解再重新装配；
  拆解/重装这套流程和本家三种傀儡的「原地换件」不一致，也更容易丢状态
  （具体退回哪些物品/数据取决于 `GolemDisintegrateMenu` 与 `GolemHolder` 的通用逻辑，未逐项实测）。

`GolemReplaceRecipe.replacePart` 本身对龙是通用的：

- `holder.getEntityType().values()` → 龙的 5 个部件；
- `part.ordinal()` → 材料索引；
- `mayEdit` 默认 true；
- 换身体时检查 `AddSlotTemplate`（龙没有，不会误触）。

所以**只需要补 5 条 JSON**，不需要改 Java。

#### 已补：2 条 `golem_add_slot` 配方（2026-10-24）

见 5.3。文件已放在 `data/dragon_golems/recipes/dragon_golem/`，不需要改 Java。

#### P1：`GolemEquipUtil` 的「成品装装备」没有龙

同 3.3 P1。如果只做右键装备，可以不改；如果要支持 Create 自动化 / `toEntityWithItem`，
需要 mixin 或上游支持。

#### P1：没有龙装备的合成/铁砧/标签

如果要做龙甲，至少还要：

- `IGolemEquipmentItem` 实现 + `isFor(TYPE_DRAGON)`；
- 配方（合成/铁砧/切石机，看设计）；
- `modulargolems:tough_item` / `modulargolems:golem_damageable` 标签（如果希望铁砧/损坏逻辑生效）；
- `DragonGolemEquipHandler` 分支；
- 渲染层/模型；
- `DragonGolemScreenControl`/Overlay 底图（可选）。

#### P2：Create/Tinkers 的铸造/序列组装配方没有龙

本家为三种傀儡的每个部件都生成了 `casting/*` 和 `sequenced_assembly/*`。
龙只有切石机。如果整合包装了 Create/Tinkers，龙部件的产线和本家不一致；
不装则无影响。属于兼容性缺口，不是核心功能。

#### P2：JEI 装配扩展对「配方 key 顺序」敏感

`GolemAssemblyExtension.setRecipeSpecial` 用 `mats.get(ind++)` 按**配方 ingredient 顺序**
取材料（`build/_mg273src/.../GolemAssemblyExtension.java:41-65`），而 `mats` 的顺序是
`GolemHolder.getMaterial` 的扫描顺序。我们的 `assemble_holder.json` 的 `key` 顺序
恰好是 W/H/B/T/L，和扫描顺序一致，所以现在没问题；如果以后调整 JSON 的 key 顺序，
JEI 预览会把材料配错。改配方时留意这一点。

#### P2：泰坦体型下装备界面的悬浮预览

`EquipmentsScreen.renderPreview` 用 `getPreviewScale()/getScale()` 做缩放
（`EquipmentsScreen.java:66-77`），而 `DragonGolemRenderer.scale` 只在
“姿势栈缩放 > 10”时才额外缩（`DragonGolemRenderer.java:48-69`）。
普通体型 18 > 10，会额外缩；泰坦 4 倍时 18/4 = 4.5 < 10，不额外缩，
最终总缩放仍是 18，龙会显得比预期大。这不是装配台的问题，是装备界面预览的
边界情况，顺手记在这里。

## 7. 我们承接了什么 / 缺了什么（一页表）

### 7.1 已经承接、不需要重做的

- `GolemType` / `GolemPart` / `GolemHolder` 注册链；
- `GolemMaterial` 材料属性、`GolemPartConfig` 权重、`magnifiers`；
- 数据包标签（`generic_parts` / `parts` / `holders` / `anvil_craft` / `golems` / `friendly`）；
- 铁砧铸材料、铁砧升级、砂轮去升级、铁砧修复；
- `GolemHolder` 序列化、`getRemaining`、`getMaxHealth`、`getHealingMaterial`；
- 装配配方 + JEI 白送的装配页；
- 工作台升级页（`GolemUpgradeMenu` / `GolemUpgradeItemHandler`）；
- 拆解台（2.7.3 通用映射）；
- 伤害事件/`GolemModifier`/`GolemAttackListener`/`getModifiersExtended`；
- `SweepGolemEntity` 横扫；
- 目标管理器、配置卡、命令手杖、模式轮盘；
- 骑乘杖入口（我们自己补的 `DragonRideHandler`）、傀儡乘客入口（`GolemCheckRideMixin`）；
- 坐骑升级 `fitsOn`（`RideUpgradeMixin`）。

### 7.2 缺的（按优先级）

| 优先级 | 缺口 | 影响 | 最小修法 |
|---|---|---|---|
| P0 | `SonicAttackGoal` 被 `stripNativeGoals` 每 20 tick 摘掉 | 幽匿身体的 MG 音波挂载是死的 | 跳过 `sonicGoals`，或不补挂本家那条 |
| P0 | 缺 5 条 `golem_replace_part` | 不能换部件 | 补 5 条 JSON（W/H/B/T/L 各一） |
| P0 | ~~缺 2 条 `golem_add_slot`~~ **已补** | 升级槽永远 5 格 | 已加钻石/下界合金两条锻造台配方 |
| P0 | CHEST 槽无任何可放物品 | 第二个护甲槽是死的 | 加 `DragonGolemEquipHandler` 的 `IGolemEquipmentItem` / 原版护甲分支 |
| P0 | `SweepGolemMenuControl.handleQuickMove` 43/44 越界 | Shift 点箭矢有崩端风险 | 改继承 `GolemMenuControl` 或覆写 `handleQuickMove` |
| P0 | 32 格扫描绕过 `predicateTarget` | 可能打配置卡/友军/乘客 | 换 `predicateTarget` + 排除乘客 |
| P1 | 没有龙护甲物品/渲染层 | 护甲属性只能靠升级 | 新 `IGolemEquipmentItem` + 模型/渲染层 + 配方 |
| P1 | 旗帜可装但不可见 | HEAD 槽视觉无效 | `DragonGolemModel` 实现 `IHeadedModel` + 加 `GolemBannerLayer` |
| P1 | `GolemEquipUtil` 无龙分支 | 成品装装备/Create 自动化不行 | mixin / 上游支持 |
| P1 | `DragonSonicGoal` 绕过 `CreateSourceEvent` | 音爆不吃改源类升级 | 改用 `breathSource(MGDamageTypes.ECHO.location())` |
| P1 | `DragonTargetSource` 没接线 | 背上的傀儡不会共享龙的目标 | 在乘客 tick 里查询 `vehicle` 并 `setTargetRaw` |
| P1 | ROUTE 不推进 stage | 巡逻只走第一个点 | 在 `DragonIdleGoal` 到达/接近时调 `getMode().tick(this)` |
| P1 | 没有目标丢失/打不到就换 | 无限追不可达目标 | 加计时器 + `setTarget(null)`，或复用 `MGConfig.targetResetTime` |
| P2 | 跳劈族 `performJump` 未接 | 部分近战升级无效 | 明确设计；要接就在 `triggerMeleeUpgradesOnDive` 里补 |
| P2 | Create/Tinkers 产线缺龙 | 兼容性不一致 | 数据生成补 `casting` / `sequenced_assembly` |
| P2 | 装备界面泰坦预览偏大 | 视觉问题 | 调整 `DragonGolemRenderer` 的 GUI 判定或 `getPreviewScale` |
| P2 | 模式参数不跟 MGConfig | 配置不统一 | 文档写明，或把常量接到配置 |

## 8. 建议的动手顺序

1. **先把「配方层」补齐**（不碰 Java，收益最大）：
   - ✅ 已补 2 条 `golem_add_slot`（钻石/下界合金）；
   - 还差 5 条 `golem_replace_part`。
2. **修 P0 代码 bug**：
   - `SonicAttackGoal` 清理；
   - `DragonGolemMenuControl` 父类/`handleQuickMove`；
   - 扫描改 `predicateTarget`；
   - `DragonGolemEquipHandler` 加装备分支。
3. **再做护甲内容**：
   - 龙甲物品 + `isFor` + 标签 + 配方；
   - `DragonGolemModel` 的 `IHeadedModel` + `GolemBannerLayer`；
   - 龙甲渲染层/模型；
   - 界面底图。
4. **最后做兼容/表现**：
   - `GolemEquipUtil` mixin；
   - `DragonSonicGoal` 源事件；
   - 乘客共享目标；
   - ROUTE 推进；
   - Create/Tinkers 数据生成。

## 9. 顺带发现（不改变上面结论）

- `breath.diminishing` 的**代码默认值是 `false`**（`DragonGolemConfig.java:475-479`，`breathDiminishing()` 的兜底也是 false），
  而 README / `docs/CONFIG.md` 写的是「默认开」。如果实机觉得龙息没有递减，先看这一条；
- `breath.damageMultiplier` 有配置项、有 getter，但**没有任何调用点**（`breathDamage()` 里没有乘它），
  改它不会生效；
- `cave.guidedDamage` 的 getter 硬编码返回 `false`（`DragonGolemConfig.java:241-245`），
  配置项/注释仍然写着“默认 true”，属于「引导喷息」这条功能被临时关掉但文档没跟上；
- `DragonGolemEntity.aiStep()` 的常驻 `noPhysics`（见 2.3 P2）**已改成洞穴内主动幽灵**：
  现在由 `cave.ghost` 控制，离开洞穴会恢复碰撞；幽灵时移动层也明确按"直线可穿墙"处理。

这些不是「缺少适配」，但会直接影响你实测时对上面几条的判断，所以单列。

---

### 附：本家类型分支清单（新种类要逐条检查的地方）

这些是 2.7.3 里以 `instanceof` / `== GolemTypes.TYPE_*` 写死、**不会自动适配新 GolemType** 的入口：

- `GolemEquipUtil.applyItemOnHolder`（成品装装备）
- `GolemEventListeners.onEquip`（装备槽判定）
- `GolemBannerLayer`（旗帜渲染）
- `MetalGolemRenderer` / `DogGolemRenderer` / `HumanoidGolemRenderer`（护甲/旗帜/手持层）
- `EquipmentsContainer` / `EquipmentsScreen`（金属 6 槽、类人皮肤按钮等）
- `RiderWandItem.ride`（只有狗会真的 `startRiding`）
- `MetalGolemEntity.checkRide` / `HumanoidGolemEntity.checkRide`（只认狗/马）
- `PlayerMixin.wantsToStopRiding`（只对狗）
- `WeaponAttackListener` / `GolemMechaBowBehavior` / `SonicCannonBehavior` 等武器逻辑（只认金属/类人）
- `GolemItems` 里的护甲/武器物品本身（`isFor` 写死三种类型）

龙已经用 mixin 或自定义代码处理了其中一部分：

- `RiderWandItem` → `DragonRideHandler`；
- `checkRide` → `GolemCheckRideMixin`；
- `RideUpgrade.fitsOn` → `RideUpgradeMixin`；
- `GolemMaterialConfig.mayApply` 的「复制式清单」→ `GolemMaterialConfigMixin`。

**没有处理的**正是本文前六节列出的那些。
