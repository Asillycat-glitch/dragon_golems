# 配置与数值：改哪儿、为什么这么改

这份文档对应 2026 那一批"小问题"修复：坐骑升级、泰坦体型压乘客、选定框、属性上限、
技能升级没效果、龙息伤害过高。每一条都写清**改在哪、为什么、怎么回退**。

## 一、配置文件在哪

```
config/l2_configs/dragon_golems-common.toml
```

和傀儡装配的 `modulargolems-common.toml`、l2library 的 `l2library-common.toml`
**在同一个目录**。这不是什么 l2library 的配置 API —— l2library 根本没提供
（`l2_configs` 这个字符串在整个库里只出现一次，就是它自己注册配置那一行），
本家也是照抄同一段：

```java
ModContainer mod = ModLoadingContext.get().getActiveContainer();
String path = "l2_configs/" + mod.getModId() + "-" + type.extension() + ".toml";
ModLoadingContext.get().registerConfig(type, COMMON_SPEC, path);
```

我们的注册点在 `content/config/DragonGolemConfig.init()`，由 `@Mod` 构造函数调用
（必须在那里：`registerConfig` 认的是"当前正在构造的那个 mod 容器"）。

> **别和 `DragonBodyConfig` 搞混**：那个是**数据包**配置
> （`data/<ns>/modulargolems_config/dragon_bodies/*.json`，管"这条龙长什么样、喷什么"，
> 见 [DATAPACK.md](DATAPACK.md)）；这里是**本地 TOML**（管"这条龙怎么打"）。
> 表现归数据包，数值开关归 TOML。

| 配置项 | 默认 | 作用 |
|---|---|---|
| `breath.diminishing` | `true` | 同一次龙息里对同一个目标是否递减伤害 |
| `breath.diminishingStep` | `0.25` | 每多命中一次降多少（第二跳 75%、第三跳 50%…） |
| `breath.diminishingMin` | `0.25` | 递减下限倍率 |
| `mount.mountUpgradeOnDragon` | `true` | 本家坐骑升级能不能装到龙身上 |
| `selection.pickRadius` | `1.0` | 头/颈/躯干/尾/双翼子箱的额外拾取半径（格） |
| `selection.bodyPickRadius` | `0.5` | 本体判定箱的额外拾取半径（格） |
| `skills.nativeSkillGoals` | `false` | 是否保留本家/compat 那批 `*AttackGoal`（"goal 族"技能） |
| `skills.diveTriggersMelee` | `true` | 俯冲撞击是否触发"纯近战族"升级（地震 / 跳劈） |
| `attributes.logOverflow` | `true` | 属性被原版上限夹掉时打日志 |
| `attributes.maxHealthCapFix` | `true` | 内置版 AttributeFix：放宽**最大生命**上限 |
| `attributes.maxHealthCap` | `100000` | 上面那条放宽到多少 |

## 二、龙息递减（"一次龙息总伤害过高"）

龙息是**唯一不吃冷却**的攻击：一轮 60 tick、每 10 tick 结算一跳 = 单体 6 跳。
每跳 `0.35 × 攻击力`，六跳全中就是 **2.1 倍攻击力**，比 30 秒一次的俯冲还高，
而它是随时可用的常态输出。

现在改成"同一轮里越打越轻"：

| 跳数 | 1 | 2 | 3 | 4 | 5 | 6 | 合计 |
|---|---|---|---|---|---|---|---|
| 倍率（step 0.25 / min 0.25） | 1.0 | 0.75 | 0.5 | 0.25 | 0.25 | 0.25 | **3.0** |
| 伤害（×0.35 攻击力） | 0.35 | 0.26 | 0.18 | 0.09 | 0.09 | 0.09 | **≈1.05 攻击力** |

- **按目标各自计数**：锥体里的每个敌人都有自己的命中次数，群攻定位不变；
- **一轮一清**：`beginBreathVolley()` 在进前摇时清空计数（AI 版 `DragonRangedGoal`、
  骑手版 `DragonRiderSkillGoal` 各一处），`endBreathVolley()` 在 goal 收工时清；
- 递减只乘在**龙息锥**上，龙弹 / 俯冲 / 音爆不受影响（它们各自有冷却）；
- 想关掉：`breath.diminishing = false`；想更狠：调小 `diminishingMin`、调大 `diminishingStep`。

## 三、坐骑升级（"骑乘升级装不到龙身上"）

本家 `RideUpgrade.fitsOn` 是整个傀儡装配里**唯一**一个覆写：

```java
public boolean fitsOn(GolemType<?, ?> type) { return type == GolemTypes.TYPE_DOG.get(); }
```

升级台（`GolemUpgradeItemHandler`）和铁砧（`CraftEventListeners.appendUpgrade`）都读它，
所以龙在装配界面里**看不到**这条升级能装 —— 而龙的载客管线（座位、驾驶、骑手指令 R/G/V）
其实早就通了。现在由 `mixin/RideUpgradeMixin` 在 `fitsOn` 的 HEAD 处补一句
"目标是龙 → 放行"（`require = 0`，本家改了也不会崩）。

装上去之后：本家会给龙加 `GolemFlags.PASSIVE` —— **AI 不再自己索敌开火、也不会被 mob 盯上**
（这就是"坐骑"的意思）；但**驾驶与骑手指令完全不受影响**，因为那几条路都不看这个 flag，
玩家按键的那几秒另有 `DragonGolemEntity.riderCombat` 放行攻击判定。

> 不想要这个改动就关 `mount.mountUpgradeOnDragon`（关掉就退回本家行为：装不上）。

## 四、泰坦体型压乘客（"泰坦升级会阻挡坐在其上的玩家"）

### 症状的机理

Forge 的多部件实体（我们的 15 个子碰撞箱）是**独立实体**，而
`Entity.getRootVehicle()` 是沿"我是不是乘客"往上爬的 —— 子箱自己永远不会是乘客，
所以它返回**它自己**，和龙本体不是同一辆"车"。原版/Forge 到处都用"root vehicle 相同"
来判断"这是不是我自己 / 我的坐骑"（准星选中、攻击过滤、推挤、移动碰撞…）。

原版 `ProjectileUtil.getEntityHitResult`（准星选中、`GameRenderer.pick`）里有两支：

```java
if (aabb.contains(射线起点)) { 无条件选中它，距离 = 0 }      // ← 不看 root vehicle！
else if (擦到箱子) { 同 root vehicle 且 !canRiderInteract() 时跳过 }   // ← 这一支才看
```

普通体型下，骑手的眼睛刚好在躯干箱顶面**上面**一点点，所以没事；
泰坦（`getScale() = 4`）时箱顶长了 4 倍、人的眼高没变，于是**眼睛落进了箱子里**，
准星被离自己 0 格的箱子无条件抓住 —— 右键交互 / 左键攻击 / 放方块全被自己的龙吃掉。
本家的犬坐骑不会犯，是因为它**根本没有子碰撞箱**（整只狗一个实体，原版那套规则天然生效）。

### 修法三条，都和"狗"对齐

1. **子箱如实回答自己属于本体**：`DragonGolemPartEntity.getRootVehicle()` 返回
   `parent.getRootVehicle()`。于是"擦到箱子"那一支会正确跳过自己的坐骑
   （想反过来让骑手能点到坐骑就得覆写 Forge 的 `canRiderInteract()`，我们没这么做）。
2. **座椅锚在模型"表面"上，下沉量按体型等比、但封顶在 1 倍那一档**（`RIDER_SURFACE_UP_PX` /
   `RIDER_SINK`）。这是"人被埋进龙身体里"的根因修复，两头都要夹：
   - 旧写法是整座标量：世界高度 = `(2.861 − 12像素 × PX) × 体型` —— **下沉量跟着体型一起放大**。
     1 倍时脚底在背脊表面下 0.85 格（腿藏进背里，看着就是"坐在背上"），
     泰坦 4 倍就沉 3.4 格，而乘客本人只有 1.8 格高 → 连头一起被埋进模型里，外面只剩一个影子；
   - 但如果反过来把下沉量做成**固定世界格**，缩小体型那一头又会翻车：缩小 4 档的龙背脊表面
     只有 0.6 格高，"沉 0.85 格"会把人塞到肚子底下（真机截图确认）。
   - 现在：先取模型背脊表面的那个点（躯干方块顶面 = 模型 y 4，见 `TORSO_UP_PX` 的注释），
     再减去 `0.85 × min(1, 体型倍率)`。于是 **`scale ≤ 1` 与旧公式严格相等**
     （1 倍时 `(2.861−4×PX)−0.85 = 1.586 = (2.861−12×PX)`；0.25 倍时 `0.397 = 1.586×0.25`），
     `scale > 1` 时下沉量钉在 0.85 格不再涨 —— 泰坦也稳稳坐在背面上。
   - 前后与座位间距仍按模型像素走（大体型时自然铺开在更宽的背上）。
3. **收的是拾取箱，不是座位**（保险）：`DragonGolemEntity.pickRadiusFor(box, configured)` 会把
   "水平罩着乘客"的那些箱子的拾取半径削到「乘客眼高 − 箱顶 − 0.05」。座椅锚到表面之后，
   乘客的眼睛在所有体型下都天然高于躯干箱顶，这条基本不会再触发；留着是给
   `selection.*` 被调大、或者乘客特别高的场合兜底。

> **座椅位置只由模型决定**（`positionRider` 里就是"模型像素 × 体型倍率"，
> `RIDER_UP_PX / RIDER_FORWARD_PX` 是唯一的旋钮），**绝不会因为判定箱或眼高被顶高**。
> 曾经试过"按判定箱顶面抬座位"的写法：那样大体型时人就浮在龙背上方、和模型对不上了 ——
> 方向搞反了，冲突要在判定箱/拾取那一侧解决。

## 五、选定 / 回收（"选定框太小，回收和选择不方便"）

三处一起补：

1. **拾取半径**（`Entity.getPickRadius()`，原版默认 0）：本体 `bodyPickRadius = 0.5`、
   子箱 `pickRadius = 1.0`。它只影响"能不能被准星选中"，**完全不动碰撞体积**。
   实际生效值还会被自动削一次：**罩着乘客的那一块不许把乘客的眼睛包进拾取箱**
   （泰坦体型时那一块会变成负数），否则骑手的准星会被自己的坐骑吃掉 —— 见上一节。
   本体刻意比子箱小，就是给这个自动收缩留余量。
2. **回收手杖的瞄准辅助**：本家 `RetrievalWandItem` 自己打的射线，谓词写死成
   `e instanceof AbstractGolemEntity` —— 我们的子箱是 `PartEntity`，**被整个过滤掉**，
   于是看着龙头/尾巴/翅膀按下去什么都点不到（本体判定箱只有 2.6 × 2.2）。
   新增 `DragonRetrieveHandler`（`PlayerInteractEvent.RightClickItem`）：
   先自己打一条**认子箱**的射线（子箱折回本体，并吃上面的拾取半径），命中之后再调用
   **本家自己的** `ItemStack#interactLivingEntity` —— 过滤、权限、收进背包全是本家的逻辑，
   我们只是"替玩家把准星掰到龙身上"。本家自己选得中（或玩家潜行 = 回收周围全部）时一概不抢。
3. **手杖射程本来就不是 3 格**：`forge:entity_reach` 只管原版攻击/`player.pick`，
   回收手杖走的是自家 64 格射线（`MGConfig.COMMON.retrieveDistance`）。

> 还没动的一条：子箱在**竖直方向**上是不是和模型完全对齐（MOUNT.md 旧文里记的"整体偏高"）。
> 这个只能开 F3 + B 对着模型实测，没有可靠的理论值；现在有了拾取半径与瞄准辅助，
> 就算箱子略偏也点得中。

## 六、属性上限（"属性极高无比，快碰到原版上限了"）

### 实测（全部对着 1.20.1 / Forge 47.4.20 的字节码核过）

`parts/dragon.json` 的部件系数合计是 **HEALTH 2.0 / ATTACK 2.0**（本家金属傀儡是 1.0 / 0.5），
所以：

| 材料 | 裸装最大生命 | 加 `size_up` L2 | 再加坐骑升级 | 原版上限 |
|---|---|---|---|---|
| 铁 | 200 | 280 | 336 | 1024 |
| 下界合金 | 600 | 840 | 1008 | 1024 |
| 幽匿 | **1000** | **1400 → 1024** | **1600 → 1024** | 1024 |

> 表里的 `→ 1024` 是**只用原版上限**时的结果（材料白铸）。默认已经开启内置属性修复
> （见本节末），所以现在 1400 / 1600 会原样生效，不再被夹。

- 只有 **MAX_HEALTH** 这条上限有实际损失（幽匿龙裸装就已经 97.7% 满，随便加一级就溢出）；
- **ARMOR 上限 30 放宽也没有实际收益**：原版 `CombatRules` 内部本来就把护甲减伤压到 80%，
  超过约 20~30 点护甲不再增加减伤；
- 其余属性（攻击、攻速、移速、飞行速度、各种 `golem_*`）实测都只用到上限的百分之几，
  连边都碰不到。

### 处理：生命倍率保持原样，用内置「属性修复」把上限抬开

龙的数值**不动**（`parts/dragon.json` 的部件系数仍是 HEALTH 2.0）：幽匿龙裸装 1000 血、
堆满升级 1600 血，超上限的部分不再被丢弃 —— 由内置的 AttributeFix 等价物接管：

```toml
[attributes]
	maxHealthCapFix = true      # 默认就是 true
	maxHealthCap = 100000.0
```

效果**等价于 AttributeFix 对最大生命那一条**（`content/config/DragonAttributeFix.java`）。要点：

- **只放宽最大生命**：只有这一条会真的被夹（见上表）。护甲上限放宽没有实际收益
  （原版 `CombatRules` 内部就封顶 80% 减伤），其余属性连上限的百分之几都没用到。
- **只升不降**：已经装了真正的 AttributeFix（或别的 mod 抬过）时值更大就不动它，两者不冲突；
  装上真正的 AttributeFix（它放宽所有属性）也不会引出副作用 —— 本 mod 的伤害与击退代码
  一律按"抗性只用来削、削到 0 为止"写（`Math.max(0, 1 - 抗性)`），
  和原版 `LivingEntity#knockback` 里 `if (!(strength <= 0))` 那道守卫同一个语义。
- **实现方式是反射，不是 mixin**：注入原版 `RangedAttribute#sanitizeValue` 属于"注入原版类"，
  需要 mixin 的 annotation processor + refmap 才能在生产环境重映射（本仓三个 mixin 全部只注入本家类，
  正是为了绕开这件事，`build.gradle` 里没有那套基础设施）。反射只改一个 double 字段，
  开发环境（`maxValue`）与生产环境（`f_22308_`）两个名字都试、**改完回读校验**，
  猜错也只会打一行警告（此时退回"被夹"的老行为）。
- 配置改完要**重启**（上限在配置加载时应用一次）。
- 不想动原版上限（整合包另有安排）就设 `maxHealthCapFix = false`，超上限的部分会重新被静默夹掉。

> `parts/dragon.json` 的 `magnifiers` 保持**空表**：生命走上面的上限开关，
> 不再靠数据去压数值。

### 现在会告诉你了

原版 `RangedAttribute.sanitizeValue` 是**静默**夹取的，所以本 mod 在
`updateAttributes` 之后加了一行日志（`attributes.logOverflow`，默认开）：

```
[attr] attribute.name.max_health 的基础值 1400.00 超过原版上限 1024.00，实际生效 1024.00（超出的部分被静默丢掉）
```

顺带说明一个重要副作用：本家属性界面用**未夹取**的 `getBaseValue()` 算 tooltip，
所以"面板上写 1024、tooltip 里写 1400"是它自己的显示问题，不是数据错误。

## 七、技能升级的挂载点（"技能升级基本没有效果"）

### 先分清三族：本家的"攻击类升级"不是按近战/远程分的

查证结论：傀儡装配**没有** `onAttack` / `onHit` 这类回调（整个 `GolemModifier` 里一个函数式参数都没有），
它所有攻击类升级按**触发方式**分三族 —— 只有中间那一族和"怎么打出去"有关：

| 族 | 例子 | 触发方式 | 龙这边怎么办 |
|---|---|---|---|
| **伤害事件族** | 药水效果（缓慢/虚弱/凋零）、目标加成、穿甲、吸血、回声、击杀特效… | **任何一次真实伤害**都会触发（`LivingAttackEvent` / `LivingHurtEvent` / l2damagetracker 的 `AttackCache` 链） | 与攻击方式无关：只要伤害源是真的（攻击者 = 龙）就全生效 → 统一走 `dealSkillDamage` |
| **纯近战族** | `EarthquakeHelper` 的地震 / 跳劈（巨兽、猫灾、Mowzie、传说怪物那些材料） | 只有 `GolemMeleeGoal` 的跳劈会问 `EarthquakeHelper.findInstance` | 龙没有近战 → **把"俯冲撞击"当成龙的近战**（`skills.diveTriggersMelee`，默认开） |
| **goal 族** | 炽焰喷射、死光、音波炮、火球雨… | 各自 `onRegisterGoals` 挂一个 `*AttackGoal`，自己生成投射物 | 默认整批摘掉；要放回来开 `skills.nativeSkillGoals` |

所以**不需要按"近战/远程"重新分类升级**（本家没这么分），需要"分配"的只有后两族：
一族接到俯冲（近战等价物），一族留在它自己的 goal 上。

### 为什么 damage 族原先也没生效（两个漏点）

```
target.hurt(带 golem 当攻击者的 DamageSource, 伤害)
  ├─ LivingAttackEvent  → onAttackTarget / onKillTarget
  ├─ LivingHurtEvent    → onHurtTarget（药水效果 / 目标加成 / 命中特效）
  └─ l2damagetracker 的 AttackCache 链 → modifySource（穿甲）/ modifyDamage / finalizeHurtTarget（吸血）
```

1. `breathSource()` 用 `new DamageSource(...)` 直接造源 —— **不会触发 l2damagetracker 的
   `CreateSourceEvent`**（那个事件挂在 `DamageSources.source(...)` 这个**私有**重载上，
   由 l2damagetracker 自己 mixin 触发），于是"穿甲 / 破魔"这类改**伤害源**的升级对龙息完全无效。
2. `DragonSonicGoal` 的伤害绕过了统一出口，无敌帧 / 命中特效的待遇和别的技能不一致。

### 现在的挂载点

```java
// DragonGolemEntity：所有技能伤害的唯一出口
public boolean dealSkillDamage(LivingEntity target, @Nullable DamageSource source, float damage, double knockback)
```

它做三件事：① 攻击者记成这条龙；② 走 `hurtWithoutFrames`（打完把目标原本的无敌帧还回去，
免得每 10 tick 一跳的龙息替别人占住无敌帧）；③ 按需击退。
伤害源由 `breathSource(...)` 提供，而它**先手动触发一次 `CreateSourceEvent`**
（`AttackEventHandler.onDamageSourceCreate(new CreateSourceEvent(registry, key, this, this))`），
返回值就是被监听器改造过的源 —— 和 l2damagetracker 自己那条路完全一致。

走这条路的：龙息、龙弹（`rocketDamage`）、骑手音爆、AI 音爆（`DragonSonicGoal`）、
俯冲撞击（`performDamageTarget`，同时也是本家 `sweep` 的挂点）。
**给龙加新技能（或者第三方想挂自己的招式）直接调 `dealSkillDamage` 即可**，其余管线自动接上。

### 近战族 → 俯冲（`triggerMeleeUpgradesOnDive`）

本家地震 / 跳劈是**唯一**挂在近战管线上的技能族，在龙身上原本是死的。现在：

- 触发点：`DragonDiveGoal.scanContact` 里"第一次撞到人"的那一帧（一轮俯冲只触发一次，
  因为地震本身是范围技，逐目标触发会把冷却烧光）；骑手按 R 的冲锋走同一个 goal，所以一样会触发；
- 判定：完全交给本家的 `EarthquakeHelper` —— 先用 `findInstance`（带目标与射程，和本家近战一致），
  打不着再退一步用 `findMountInstance`（"载具式撞击"，狗骑乘时用的那条）；
- **本家自己的闸门照旧**：带着乘客的傀儡不震地、被动（装坐骑升级）的龙不震地（那是本家的规则，
  我们没动）；各自 modifier 的冷却与射程也照旧；
- 开关：`skills.diveTriggersMelee`（默认开）。

## 八、飞行寻路（B 档：A* 出航路点，龙只负责按航路点飞）

### 症状的机理（"老是隔着墙寻找目的地"）

龙的移动是 goal 直接写 `deltaMovement`（`setDiveVelocity`），而**目标点从来不校验可达性**：

- `DragonIdleGoal.sampleWaypoint()` 在 `[5, 24×速度倍率]` 环带里随机采样，只做一件事 ——
  重采几次避开"落点下方是液体"，**不看视线、不看连通性、不看这条龙塞不塞得进去**；
- `RETURN` 的目标就是软中心（= 主人的 XZ）——主人躲进屋/山洞，龙就朝那个 XZ 一直推；
- 撞墙之后不是绕路，而是 `DragonFlightAssist` 在扇面里找"塞得下"的方向硬挤，
  长时间卡住还会进 no-clip 救援 / 常驻幽灵。

更根本的一条：**"寻路"从来没被调用过。** 全 mod 只有三处碰 `getNavigation()` ——
`DragonFlightAssist` 的 `recomputePath()`、读路径点、`DragonFollowTeleportGoal` 的 `stop()`，
<b>没有任何一处 `moveTo(...)`/`createPath(...)`</b>。于是 `PathNavigation.targetPos` 恒为 null，
`DragonFlyingNavigation.recomputePath()` 第一行就返回，路径一次都没生成过，
"顺着寻路路径点避障"那条分支是死代码。

### 修法：只借路线，不借方向盘

`DragonPathFollower`（服务端，每只龙一个）：

1. 自己用 `PathFinder` + `DragonFlyNodeEvaluator` 算 A*（参数和原版
   `PathNavigation.createPath(pos, reach)` 一致：搜索区域 = 当前位置 ±(跟随距离 + 8)，
   路线长度上限同样是这个值）；
2. 每 tick 把"还没走到的第一个节点"作为**转向目标**交给 goal，节点走完就前进一个；
3. 目标点只决定"飞多快"，**速度与悬停高度仍归原来的 goal** —— 龙的手感不变；
4. 路线是**截断**的（`Path.canReach() == false`，即 A* 只能走到离目标最近的可达点）时
   `noRoute()` 为真：游走 goal 立刻换点，而不是继续对着墙拱；战斗靠拢则沿截断路线飞到
   最远处停下（最后一个节点），也不撞墙。

**为什么不干脆让原版 `FlyingPathNavigation` 开龙**：`PathNavigation.tick()` 只要发现
`path != null` 就会去驱动 `MoveControl`，而这条龙的高度是"脚下地形 + 悬停高度 + 呼吸"
算出来的（还要被头顶天花板夹住），交给 `MoveControl` 等于把这套全部推翻 ——
所以跟随器<b>一个字段都不写回导航对象</b>，两套逻辑不打架。

### 体型余量（`pathfinding.bodyClearance`）

原版 `FlyNodeEvaluator` 判定节点只看**方块格**：实心方块是 `BLOCKED`（malus −1）会被排除
（所以它本来就不会规划穿墙路线），但它**完全不看实体多大** ——
`NodeEvaluator` 里按体型算出来的 `entityWidth/Height` 在飞行这一支里没参与判定。
2.6 格宽（泰坦 10 格宽）的龙因此会被规划进"1 格缝"，然后一路蹭墙。

`DragonFlyNodeEvaluator` 在 `super` 判为可走之后再按**龙自己的身体盒子**采样：
四个角柱 + 中柱 × 三个高度（共 15 个采样点），任何一个点落进有碰撞箱的方块 → 判 `BLOCKED`。
采样尺寸封顶 8 格（泰坦下逐方块遍历会变成性能黑洞），异常一律按"能过"处理。

### 可调项

| 键 | 默认 | 说明 |
|---|---|---|
| `pathfinding.enabled` | true | 关掉退回旧行为（直奔目标 + 卡住硬挤），用于对照排查 |
| `pathfinding.repathTicks` | 20 | 每隔多少 tick 重算路线（目标移动超过 2 格会立刻重算） |
| `pathfinding.bodyClearance` | true | 寻路时是否考虑龙的体型（见上） |
| `pathfinding.giveUpTicks` | 40 | 连续算不出路线这么久就认定目标不可达 |
| **`pathfinding.smoothingLookahead`** | **6** | **路线拉直**：每 tick 从当前节点往后看 N 个，取"直线（方块射线）不被挡的最远节点"当转向点；0 = 关闭 |
| **`pathfinding.bodyMargin`** | **0.35** | 评估器给身体额外留的余量（格）：采样盒子每边向外放宽，让路线自己离墙/梁远一点 |

### 路线拉直（string-pulling）

A\* 的节点是逐格搜出来的锯齿，照着"下一个节点"飞会**一直小幅修正方向**（抖），而且容易贴着转角蹭过去。
现在每 tick 从当前节点往后看 `smoothingLookahead` 个节点，取**直线可见**的最远那个当转向点，
中间的节点直接标成已走（`[fly] skip=` 就是这一 tick 跳过了几个）。
这是经典路径拉直：**路线更直、转向更少、离墙更远**，代价是每 tick 最多 N 次方块射线（很便宜）。

## 九、旋转（转向速度与压弯）

真机反馈"龙旋转效果不够好"。原来的数值写死在代码里（基础 18°/tick、转弯半径 4.0、
玩家驾驶下限 6°/tick、侧倾 1.2×28°），现在全部进配置，并且把两组明显偏保守的值抬了一档：

| 键 | 原值 | 现在 | 作用 |
|---|---|---|---|
| `flight.turnRateBase` | 18 | **22** | 慢速时的转向下限（度/tick）。22 ≈ 0.8 秒掉头 180° |
| `flight.riderTurnRate` | 6 | **10** | 玩家驾驶时的转向下限：6°/tick 掉头要 1.5 秒，跟不上鼠标 |
| `flight.turnRadius` | 4.0 | **3.4** | 转弯半径（格），`ω = v / r`：调小更灵活、调大更沉 |
| `flight.turnSizePenalty` | — | **0.0** | `转速 ÷ (1 + 惩罚 × (体型倍率 − 1))`；0.35 时 4 倍泰坦约 ÷2（转起来更"重"） |
| `flight.maxRoll` | 28 | **35** | 侧倾上限（度） |
| `flight.rollPerDegree` | 1.2 | **1.5** | 每 1° 转速折成多少度侧倾（压弯更明显） |

- 转向速度仍然是"慢速取下限、高速按转弯半径一起涨"（`turnRateFor`），上限 96°/tick；
- 玩家驾驶的上限是 `max(riderTurnRate, turnRateFor(速度) × 0.5)` —— 一半是故意的：
  鼠标一抖机头不跟着抽（这条龙 26 格长、尾巴 70 格，瞬间掉头看着像原地拧）；
- 侧倾由"本 tick 实际转过的角度"低通后算出来（AI 巡航、鼠标转、俯冲航线接管都有压弯），
  超过物理转速上限的转角（瞬移、强制对齐）不算，免得机身猛横。

## 十、巡航轨迹（"到目的地后顿住来回移动"）

### 前提：巡航是"永不刹停"的

原始设定是"龙在天上时一直保持移动"——因为要维持扇翅的观感。实现方式是
`DragonIdleGoal` 的速度下限 `MIN_SPEED`（到点不刹停、立刻换下一个采样点）+
`WANDER_REACH`（水平走够近就算"到了"）。这个设定本身没问题，
问题出在"换点那一瞬间"和"换点的频率"上。

### 三条原因

1. **速度矢量一瞬间反向**（主因）。到点后立刻采样新点，若新点在身后，
   `vx / vz` 当场反号 —— 而模型只能按 `turnRateFor`（22°/tick）慢慢转，
   于是"位移已经往回走了、朝向还没转过来"，看起来就是**顿一下再往回蹭**。
   转向速度治不了它：那一瞬间错的不是转向，是轨迹。
2. **换点频率失控**（本次 B 档引入的）。新加的 `pathfinding` 判定
   `noRoute()`（目标够不着）**每 tick 都会为真**，于是游走每 tick 重采一个点、
   目标每 tick 跳一次 —— 表现是原地抖。已加 10 tick 的最小间隔
   （`RESAMPLE_COOLDOWN`）；到点/超时/跑出活动范围这些"稳定事件"照旧立刻换。
3. **采样方向允许大掉头**。原来的前方扇面是 ±120°，还剩三分之一的概率要掉头，
   已收紧到 ±100°（重试 6 次，实在不行才认账）。

### 修法

| 位置 | 做了什么 |
|---|---|
| `DragonGolemEntity.steerVelocitySmooth` | 轨迹平滑：<b>水平分量</b>按 `flight.trajectoryLerp` 从当前速度过渡到期望速度；**竖直分量直接采用**（悬停是双向收敛的，迟一 tick 就会顶天花板或沉下去） |
| 游走 / 返航 / 靠拢 / 音波靠拢 | 全部改走这个平滑入口（俯冲、骑手指令这种"脚本轨迹"仍走原来的直写） |
| `DragonIdleGoal.RESAMPLE_COOLDOWN` | "够不着"换点最小间隔 10 tick |
| `DragonIdleGoal.FORWARD_ARC` | 采样前方扇面 ±100°、重试 6 次 |
| `DragonPathFollower.MIN_REPATH_TICKS` | 两次 A* 之间至少 4 tick（目标乱跳时的限流，防止每 tick 算一次寻路） |

### 一个附带事实

拍翅动画**本来就有悬停基准频率**（`DragonGolemModel` 的 `FLAP_BASE_RATE`，
25 tick 一轮 ≈ 0.8 次/秒，速度越快扇得越急）—— 也就是说"必须一直移动"
并不是动画不变突兀的**必要条件**（待机沉降状态本来就是原地悬停的）。
所以如果以后想要"走走停停"的巡逻（到点悬停一会儿再走），
在 `DragonIdleGoal` 里给"到点"加一个 hold 计时即可，不需要动动画。
当前仍保持"永不刹停"的设计，只是把顿挫消掉了。

## 十一、瀑布 / 流水（"穿过可以穿越的瀑布时抽搐"）

流水（瀑布、水渠、岩浆瀑布）在物理上**没有碰撞箱**，所以"能不能穿过去"从来不是问题 ——
问题出在**悬停参照系**和**水流推力**这两条上：

1. **悬停参照系把瀑布顶当成了支撑面**（主因）。悬停高度 = `scanFloor(...) + hoverHeight`，
   而 `scanFloor` 撞到液体时会往上找**整根液柱的顶面**（`liquidTopY`，上限 `LIQUID_SCAN`）。
   瀑布柱可以很高，于是龙一旦飞进去：支撑面 = 瀑布顶 → 悬停目标整体抬高 → 往上挪一格 →
   下一 tick 支撑面又跟着抬 …… <b>一格一格顺着瀑布往上爬</b>；
   而从瀑布边缘蹭出去的那一瞬支撑面又掉回地面 —— 悬停目标在"瀑布顶"与"地面"之间反复跳，
   就是真机看到的抽搐。
2. **水流推力在和控制端打架**。原版会把流体流向施加到实体上（浮力与游泳是另一套），
   而这条龙是"悬停 + 每 tick 由 goal 直接写速度"的单位：瀑布里竖直方向被水往下拽、
   控制端在往上抬，两边互相抵消又互相超调，看起来就是原地抖。

### 修法：只认**静水**，流水不算支撑面

| 位置 | 做了什么 |
|---|---|
| `scanFloor` | 撞到**流水**（`FluidState.isSource() == false`）时不再当支撑面，继续往下找真正的地面 / 静水液面 |
| `liquidTopY` | 往上数液柱时**遇到流水就停** —— 否则"瀑布砸进水池"那种地方会从池水一路数到瀑布顶、又把人带上去；停下来那格才是**静水水体的真正液面** |
| `isPushedByFluid()` | 改成可配置，默认**不被水流推动**（浮力 / 游泳逻辑不受影响，那条不看这个开关） |

为什么"只认静水"是对的：自然水体（湖泊、水池、海洋）**都是静水方块**，
玩家挖的水渠与瀑布**都是流水**。于是：

- 湖上悬停 ✓ 照旧（静水液面）；
- 泡在深水池里 ✓ 照旧以静水液面为准 →"自己爬出水池"这条能力不受影响；
- 飞进瀑布 ✓ 流水被跳过 → 支撑面直接落到瀑布底下的地面 / 池面（稳定），不爬升、不跳变；
- 一格深的流水水渠 ✓ 忽略它，按底下真正的地面算，高度差最多 1 格。

### 可调项（新配置节 `[hover]`）

| 键 | 默认 | 说明 |
|---|---|---|
| `hover.flowingFluidIsFloor` | false | true = 旧行为（流水也算支撑面，会顺着瀑布往上爬） |
| `hover.pushedByFluid` | false | true = 原版行为（水流 / 岩浆流推得动龙） |

## 十二、俯冲与天花板（"龙尝试穿过天花板俯冲，但没能成功"）

### 症状与根因（对着代码算出来的数）

地牢房间：**天花板 40 格、作战平台 30 格往上、安全点在 34 格以上**。龙已经进了房间、被围住，
传送又落不下去（拥挤的高台，候选点全 `noCollision` 失败），于是它只能靠俯冲 —— 而俯冲的航线高度是

```
edge = groundHeight(x, z) + entryAlt()        // entryAlt() = max(12, hoverHeight() + 2)
```

**原来完全没有天花板约束**，代入这个场地：

| 体型 | `groundHeight`（平台面） | `entryAlt()` | 航线顶点 | 天花板 |
|---|---|---|---|---|
| 1 倍 | ≈30 | max(12, 10+2) = **12** | **42** | 40 → 高出 2 格 |
| 泰坦 4 倍 | ≈30 | max(12, 20+2) = **22** | **52** | 40 → 高出 12 格 |

所以它每一轮俯冲都在命令自己爬到**天花板外面**去"对线"。而撞天花板是**竖直**碰撞：

- `horizontalCollision == false`（不累加 `totalHits`、`stuckTicks` 不涨）；
- 没有嵌进方块（`noRoom == false`，`embedded` 一直 false）；
- 于是"自动脱困 / 常驻幽灵"三条通道**一条都不响**，它只能卡在 `LINEUP` 等 160 tick（8 秒）超时，
  一轮一轮重试，同时被围住集火。

所以这不是"穿墙阈值太严"，而是**它根本不该往那个方向飞**。

### 修法两条

| 位置 | 做了什么 |
|---|---|
| `DragonDiveGoal.curvePoint` | 航线端点按**该点所在坐标**的天花板夹住（`DragonGolemEntity.ceilingYAt(x, z, fromY)`），再留 `ceilingMargin() = 1.5 × 体型 + 1` 格给模型下垂的那段。平台 30 / 天花板 40 时，1 倍顶点从 42 压到 **37.5**，泰坦从 52 压到 **33** |
| `DragonGolemEntity.diveRoomOk` + `DragonDiveGoal.canUse` + `pickSkill` | 目标上方空间低于 `diveMinRoom() = 4 + 1.5 × 体型`（1 倍 5.5、泰坦 10 格）时，**AI 干脆不抽俯冲**，这一轮交给龙息 / 龙弹 / 音爆（这三招不需要爬升）。骑手指令（按 R）不受影响 —— 那是玩家明确要的，航线压平后照打 |

顺带把 `hoverCeilingY()` 拆成了可传坐标的 `ceilingYAt(x, z, fromY)`：俯冲端点离龙有十几到几十格
（泰坦 64 格），拿**龙脚下**的天花板去夹航线是错的，必须按端点位置扫。

### 可调项

| 键 | 默认 | 说明 |
|---|---|---|
| `skills.diveRoomCheck` | true | 关掉 = 旧行为（只剩天花板夹取，AI 仍会尝试够不着的高入场点） |

## 十三、幽灵（穿墙）只在**战斗外**进入

### 现象与判断

穿墙时**龙息与技能会被方块挡住**，等于整场战斗无效。这个代价本身是对的（穿墙就该有代价），
但如果允许**战斗中进入**幽灵，就会出现最糟的组合：

1. 龙被围住 / 追不到人 → 自动穿墙（脱困或累计撞墙的常驻幽灵）；
2. 穿墙之后打不到任何傀儡 → **整场战斗都白打**；
3. 而且它会一直挂在幽灵态里（常驻幽灵要连续 3 次、每 30 秒一次的干净检查才退出，约 90 秒）。

所以：**自动幽灵只在战斗外进入。**

### 判定与实现

| 项 | 内容 |
|---|---|
| "战斗中"判据 | `DragonGolemEntity.inCombat()` = <b>有目标</b> 或 <b>最近 `ghost.combatMemoryTicks`（默认 100 tick = 5 秒）内打过 / 被打过</b>。单看 `hurtTime`（只有 10 tick）太短，被围在墙边挨一轮、间隙一过又算"非战斗" |
| 自动脱困 | 战斗**中不开始**；已经开始的一旦进入战斗，**立刻收工**（`endRescue`） |
| 常驻幽灵（累计撞墙 60 次） | 战斗**中不进入**；已经开着的在战斗中**挂起**（标志保留），战斗结束立刻恢复 |
| noPhysics 写入口 | `freeFlight \|\| (rescue 且非战斗) \|\| 正嵌在方块里 \|\| (常驻幽灵 且非战斗)` |
| **不**受约束的两条 | ① 玩家按 **H** 的手动自由飞行（明确选择，代价自负）；② **"正嵌在方块里"的 no-clip** —— 那是**物理安全**（不开会被碰撞解算每 tick 弹飞十几格），不是战术穿墙 |

### 可调项（新配置节 `[ghost]`）

| 键 | 默认 | 说明 |
|---|---|---|
| `ghost.duringCombat` | false | true = 旧行为（战斗中也会自动穿墙），用来做 A/B 对照 |
| `ghost.combatMemoryTicks` | 100 | "战斗中"的记忆窗口（tick）；调大 = 打完架更久才算脱战 |

## 十四、射界与索敌（"优先打看得见的敌人"）

### 为什么会"隔着墙喷"

两处原因叠在一起：

1. **本家的 `hasLineOfSight` 不算数**：`AbstractGolemEntity.hasLineOfSight` 在傀儡带
   `SEE_THROUGH`（某些材料的"透视"升级）时<b>直接返回 true</b> —— 穿墙也算"看得见"。
   而龙息锥体与喷流<b>都会被方块挡住</b>（`breathReach` 就是这么算的），
   所以以前那句 `los` 只能保证"没有透视升级的龙"能判对。
2. **索敌是"就近优先"**：本家的 `Golem3DTargetGoal` 用 `TargetingConditions` 按距离打分
   （`mustSee=false`、`mustReach=false`），"最近的那个敌人"经常隔着墙 —— 于是它对着墙喷满一整轮，
   墙外真正能打的敌人没人管。

### 修法 A：射界改用**真实方块射线**

`DragonGolemEntity.hasClearShotTo(target)`：`level.clip(嘴 → 目标胸口, Block, NONE)`，
撞到方块且距离明显小于目标距离 → 判定被挡（留 0.5 格容差，避免目标贴墙时擦到脚下方块）。

替换掉原来的 4 处 `hasLineOfSight`：`DragonRangedGoal` 的 `canUse` / `canContinueToUse` / `tickCloseIn`，
以及 `pickSkill` 的 `los`。效果：**不再对着墙喷**，龙弹/音爆同理。

### 修法 B：换掉索敌（射界加权）

新增 `DragonTargetGoal`（替换本家的 `Golem3DTargetGoal`，同优先级 3、同间隔 5 tick）：

```
分数 = −距离 + 射界通畅(40) + 正在攻击主人(30) + 当前目标(25)
```

| 项 | 作用 |
|---|---|
| −距离 | 越近越好（和本家一致） |
| **射界通畅 +40** | 直接对应"优先索敌不被方块阻挡的敌人"。距离项是格数，所以 40 分 ≈ "愿意多飞 40 格去打一个打得着的敌人" |
| 攻击主人 +30 | 保护职责优先 |
| 当前目标 +25 | **滞回**：没有它，两个分数接近的目标会让龙每 5 tick 换一次，龙息永远打不满一轮 |

"该不该打"的规则**完全复用本家**（`predicateTarget` + `canAttack`）——阵营、模式、愤怒、主人关系、
和平模式那些判定一条都不会丢；我们只决定"在能打的里面挑哪个"。搜索框是<b>三维</b>
（原版 `inflate(r, 4, r)` 的 ±4 竖直对飞在 10~20 格高的龙太小）。

### 可调项

| 键 | 默认 | 说明 |
|---|---|---|
| `skills.targetClearShotBonus` | 40 | "射界通畅"值多少分；**0 = 退回纯距离优先（本家行为）**，用来 A/B |

## 十五、高度控制与寻路统合（"机身穿进天花板"）

真机截图：龙整条机身埋在天花板里。根因很直接 —— **天花板夹取用的是"脚部"的 Y，完全没扣身体高度**：

```java
// 旧：hoverTargetY = min(局部地面 + 悬停高度, hoverCeilingY())
//     hoverCeilingY() 只返回"天花板方块 − 1"，那是"头能碰到的高度"，不是"脚能站的高度"
```

判定箱是 `bbHeight`（1 倍 **2.2** 格、泰坦 **8.8** 格），于是：

| 体型 | 旧上限（脚） | 身体顶部实际到达 | 穿进天花板 |
|---|---|---|---|
| 1 倍 | 天花板 − 1 | 天花板 + 1.2 | **1.2 格** |
| 泰坦 4 倍 | 天花板 − 1 | 天花板 + 7.8 | **7.8 格** |

正确的关系是 `脚 + 身体高度 ≤ 天花板方块下沿`。

### 统合后的三条规则

1. **悬停上限扣身体高度**（`maxFeetYUnderCeiling`）：
   `脚上限 = 天花板方块 Y − bbHeight − flight.ceilingClearance(0.25)`。
   房间比龙还矮时（本来就塞不进去），贴着 `局部地面 + 0.5` 悬停，不沉进地板。
2. **寻路的终点用"目的地自己的悬停高度"**（`hoverTargetYAt(x, z)`）：
   以前 `DragonPathFollower` 拿"龙的当前 Y"当终点高度，A* 只能搜出一条贴着当前高度的走廊；
   现在按<b>目的地</b>的地面与天花板算高度，路线会自然地从高处绕、或爬出低房间。
3. **竖直方向交给寻路**（`DragonPathFollower.onRoute()` + `flightVerticalTarget()`）：
   A* 的节点本来就带空地判定（评估器按身体盒子逐节点采样），所以<b>路线本身就是"哪儿能飞、飞多高"的答案</b>。
   - 在航线上时：竖直目标 = **航路点 Y**（爬升/下降跟着路线走，翻过梁、绕开隔层、爬出低房间都靠它）；
   - 路线走完 / 没有路线时：才回到**悬停高度**（待机、悬停开火、`holdHover()` 都是纯悬停）。

   以前只借路线的水平走向、竖向永远听悬停的 —— 于是"高台上方 N 格"这种目标被天花板挡住时，
   没人告诉它那个高度没有空间，悬停逻辑只会**一直往上顶**（"它一直尝试向上飞"）。
   `pathfinding.verticalDeadband`（默认 1 格）现在只是防微抖：航路点高度和悬停高度几乎相同时不动。

### 可调项

| 键 | 默认 | 说明 |
|---|---|---|
| `flight.ceilingClearance` | 0.25 | 天花板与身体之间的余量（模型翅膀比判定箱略高，别设太小） |
| `pathfinding.verticalDeadband` | 1.0 | 防微抖死区；调大更像"定高巡航"，调小更严格贴航线起伏 |

## 十六、诊断日志（看它到底怎么飞的）

配置里打开 `debug.flightLog = true`（默认关），日志里会多出两类行。**这是排查"它为什么这么飞"的正规入口** ——
龙的移动是三层：goal 给目标点 → `DragonPathFollower` 算 3D 路线 → 水平/竖直速度。从外面只能看到"它在乱飞"，
这几行把三层的中间量全部摊开。

### `[move]`：每 `debug.flightLogInterval` tick（默认 10 = 0.5 秒）一行

```
[move] #1234 goal=(122.50, 0.00, 1262.00) pos=(122.86, 44.80, 1262.39) | steer=(125.50, 46.00, 1263.50) onRoute=true
       | vert: target=46.00 来源=路线 hover=39.75 floor=30.00 ceilBlock=41.00 maxFeet=38.75
       | vel=(0.300, 0.120, -0.200) | hColl=false vColl=true noPhys=false embed=false
       | goals=DragonIdleGoal
```

| 字段 | 含义 |
|---|---|
| `goal` | **goal 交给寻路的目标点**（游走点 / 目标位置 / 主人位置） |
| `steer` + `onRoute` | 航路点（带 Y）以及它是不是"航线上的节点" |
| `vert: target / 来源` | 竖直目标，以及它是**跟路线**还是**悬停** |
| `hover` / `floor` / `ceilBlock` / `maxFeet` | 悬停高度 / 局部地面 / 头顶天花板方块 Y / 天花板允许的最高脚部 Y |
| `hColl` / `vColl` / `noPhys` / `embed` | 水平碰撞 / 竖直碰撞 / no-clip / 是否嵌在方块里 |
| `goals` | 这一 tick 正在跑的 goal（能看出是"巡航"还是"战斗/俯冲"） |

### `[path]`：每次重算路线一行

```
[path] recompute goal=(122.50, 1262.00) goalY=46.00 reach=1 → nodes=14 canReach=true
       前6个节点=(122,46,1262)(123,46,1262)(124,45,1263)...
```

- `goalY` 是**目的地的悬停高度**（那儿的地面 + 悬停高度，被那儿的天花板按身体高度夹住）；
- `nodes` / `canReach`：路线长度与"能否真正到达"（`canReach == false` = 截断路线，只到最近的可行点）；
- **前 6 个节点**能直接看出它是"平飞"还是"先爬升/绕行"。

### `[move] 正在上升但头顶被挡`：WARN，1 秒限流

```
[move] 正在上升但头顶被挡：vy=0.150 vertTarget=38.75 hoverTarget=38.75 steerY=na onRoute=false
       floor=30.00 ceilBlock=41.00 maxFeet=38.75 pos=(...) goals=DragonIdleGoal
```

这一行就是"它还在想往上飞"的现场：`vertTarget` 已经等于 `maxFeet`（= 天花板允许的最高）却还有向上速度时，
多半是**某个启发式在硬抬**（历史上就是 `DragonIdleGoal` 的"蹭到方块就抬 0.15"，现在它已经检查 `hasHeadroomFor`）。

### 可调项

| 键 | 默认 | 说明 |
|---|---|---|
| `debug.flightLog` | false | 打开上面这些行 |
| `debug.flightLogInterval` | 10 | `[move]` 的间隔（tick） |

## 十七、飞行执行层（`DragonFlightController`）

### 为什么要有它

到 0.4 后期，"谁能写 `deltaMovement`"已经散落在五处：实体 `aiStep` 的 if-else 链、四个 goal
（待机/远程/音波/俯冲各写 `diveVelocity`）、飞行辅助的避障、悬停、脱出。每修一个 bug 就多一条
否决规则，而规则之间互不知情 —— **抽搐 / 穿墙 / "想往上飞" 都是这么来的**
（真机事故：脱出分支排在 goal 速度之后 → 成了死代码 → 带着 no-clip 在墙里上升）。
所以 S0+S1 把执行层收成一条流水线：

```
goal（只表达意图）
   │  setDiveVelocity(...) / 悬停高度 / 脱困 / 俯冲航线
   ▼
DragonFlightController.tick(rider)          ← 每 tick 唯一的速度出口
   ① 选模式（优先级写死在一处）：
      RIDER_DIVE 骑手冲锋 > RIDER 骑手驾驶 > ESCAPE 重合脱出
      > SCRIPT 技能航线 > RESCUE 自动脱困 > HOVER 悬停
   ② 该模式算出"期望速度"
   ③ 唯一安全层：竖直分量夹进 flightBand()
   ④ 写 deltaMovement 一次
   ▼
Entity.move()（原版物理）
```

### `flightBand()`：高度的唯一真相

```
minFeet = 局部地面 + 0.5                          （别沉进地板）
maxFeet = 头顶天花板 − 身体高度 − ceilingClearance  （别顶穿天花板；无天花板 = +∞）
fits    = minFeet ≤ maxFeet                       （房间比龙还矮 → false）
```

控制器拿到任何期望速度后：

```java
if (getY() + v.y > band.maxFeet) v.y = min(v.y, 0);   // 带外上方：绝不许再上升
if (getY() + v.y < band.minFeet) v.y = max(v.y, 0);   // 带外下方：绝不许继续下沉
```

于是"顶着天花板往上飞"在**构造上**不可能发生，不需要再给每个模式单独打补丁。
这条同时替代了以前两处重复补丁：`flightVerticalTarget` 里的 `verticalCollision` 否决、
以及飞行辅助里"重合时把向上速度归零"——**速度出口只剩一处**。

### 这一版改了什么 / 没改什么

| | 内容 |
|---|---|
| **S0（搬家）** | `aiStep` 的 if-else 链、`applyHover` 的竖直计算、骑手冲锋的诊断日志，原样搬进控制器；实体只留 `flightController.tick(rider)` 和压弯/诊断 |
| **S1（收口）** | 引入 `flightBand()` 与唯一安全层；删掉上述两处重复否决；`applyHover()` → `hoverYSpeed()`（只算竖直分量，水平由执行层保留） |
| **没改** | goal 的决策（何时喷息 / 抽不抽俯冲 / 打谁）、悬停目标高度的算法、寻路、渲染、配置、配方 |

### S2 / S3 进展

| 阶段 | 内容 |
|---|---|
| **S2（已做）** | 俯冲弧线的**每个点**都夹进"该点自己的 `flightBandAt(x,z)`"：天花板低 → 弧线被压扁 → 降级成**浅掠**（横向轨迹、接触判定、伤害全保留）。删掉两道"拒绝门"（`diveRoomOk` / `hasHeadroomFor`）与 `verticalCollision` 中止 |
| **S3-a（已做）** | 执行层拥有"意图卡住"的判定权：招式意图带水平分量且正在水平碰撞 → 累加 `blockedTicks`；连续 **10 tick** → 判定"这个意图在此地形执行不了"，**水平分量清零（原地悬停）**；并通过 `flightIntentBlockedTicks()` 把数字交给 goal（俯冲被挡 30 tick 就主动收招） |
| **S3-b（未做）** | goal 侧进一步收口成 `WANDER / ENGAGE / SCRIPT` 三种显式意图对象（把 `setDiveVelocity` + `setKeepAim` + `setBodyRollDirect` 合并成一个不可分状态的 `FlightIntent`） |
| **S4（未做）** | ESCAPE / 幽灵规则（含"战斗中不穿墙"）也收进控制器 |

> 设计原则（来自真机反馈）：**招式只"请求一段轨迹/一个站位"，能不能飞由执行层判定，
> 空间不够时降级（浅掠/原地悬停）而不是禁用**。之前"只有不需要移动的龙息弹是稳的"，
> 就是因为它是唯一不跟移动层抢控制权的招式。

### 空间预算（平地 / 山洞的行为分叉）

开发以来所有难点的共同原因：**龙傀儡本体太大**（1 倍就 26 格长、2.6 格宽，绕圈半径 20+）。
在山洞/工厂里，"边飞边绕圈扫射"这种动作**物理上就没有空间**，继续做只会贴着墙抽搐。

所以不按"边缘情况"打补丁，而是先量空间、再**按空间选动作**（`DragonGolemEntity.spaceBudget()`）：

```
headroom  = 头顶天花板 − 脚 − 身体高度     （无天花板 = 999）
clearance = 8 个方向"整条龙塞得下"的最远距离，取中位数（上限 24 格）
roomy     = headroom ≥ 2 且 clearance ≥ orbitRadius × 1.5
```

| 场地 | `roomy` | 喷息时的动作 |
|---|---|---|
| 平原 / 开阔地 | true | **绕圈扫射**（观感优先），半径不超过实际净空 × 0.7 |
| 山洞 / 工厂 / 窄房 | false | **原地悬停扫射**（不旋转、不贴墙） |

每 10 tick 量一次（8 方向 × 最多 12 次包围盒探测），诊断行里是 `roomy= clr= hd=`。

## 十八、回退清单（哪条不想要就关哪个）

| 不想要的效果 | 怎么办 |
|---|---|
| 龙息递减 | `breath.diminishing = false` |
| 坐骑升级能装龙 | `mount.mountUpgradeOnDragon = false` |
| 拾取半径变大 | `selection.pickRadius = 0` / `bodyPickRadius = 0` |
| 回收手杖认子箱 | 暂时没有单独开关（它只在本家选不中时才接管） |
| 属性日志刷屏 | `attributes.logOverflow = false` |
| 不要放宽最大生命上限 | `attributes.maxHealthCapFix = false`（超上限的部分会重新被夹掉，也可以改装 AttributeFix） |
| 让 `*AttackGoal`（goal 族）回来 | `skills.nativeSkillGoals = true` |
| 俯冲不再触发地震/跳劈 | `skills.diveTriggersMelee = false` |
| 寻路（退回直奔目标 + 硬挤） | `pathfinding.enabled = false` |
| 寻路别管龙的体型 | `pathfinding.bodyClearance = false` |
| 转向太灵活/太沉 | `flight.turnRadius` 与 `flight.riderTurnRate` |
| 泰坦转弯像雨刷 | `flight.turnSizePenalty = 0.35` |
| 压弯太夸张 | `flight.maxRoll` / `flight.rollPerDegree` 调小 |
| 到点还是有点顿 | `flight.trajectoryLerp` 调小（0.15 更飘、1.0 = 旧的不平滑行为） |
| 想让流水也算支撑面（旧行为） | `hover.flowingFluidIsFloor = true` |
| 想让水流推得动龙（原版行为） | `hover.pushedByFluid = true` |
| 室内也不想打俯冲（纯远程） | `skills.diveRoomCheck = true`（默认）+ 把 `skills.nativeSkillGoals` 关着；想反过来回到旧行为就设 `skills.diveRoomCheck = false` |
| 战斗中也要自动穿墙（旧行为） | `ghost.duringCombat = true` |
| 索敌退回纯距离优先（本家行为） | `skills.targetClearShotBonus = 0` |
| 天花板余量太大（贴得离顶太远） | `flight.ceilingClearance` 调小（0 = 头顶正好贴天花板） |
| 不想让航线改变飞行高度 | `pathfinding.verticalDeadband` 调大（比如 20 = 基本只认悬停高度） |

