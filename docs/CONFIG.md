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
| `cave.ghost` | `true` | 洞穴里 AI 直接穿墙（无骑手时）；关闭后交回自动脱困 |
| `cave.lavaClearance` | `3.0` | 洞穴战斗时脚底离岩浆液面的最小高度（格） |
| `cave.attackHeight` | `4.0` | 洞穴战斗时站在目标头顶上方多少格 |
| `cave.standoffFactor` | `1.0` | 洞穴站位倍率（调大 = 站得更远） |
| `pathfinding.enabled` | `true` | 寻路总开关；直飞干净时不跑 A* |
| `pathfinding.lavaMargin` | `0.6` | 航点与岩浆额外保持的距离（格） |
| `crystal.guard` | `true` | 附近水晶是否给龙加护甲/韧性 |
| `crystal.explosionImmune` | `false` | 是否免疫"末地水晶自爆"（只限水晶直接来源，不是全爆炸免疫；默认关，和原版一致） |
| `crystal.armorPerCrystal` | `4.0` | 每块存活水晶提供的护甲 |
| `crystal.armorCap` | `16.0` | 水晶护盾的护甲上限 |
| `crystal.toughnessPerCrystal` | `2.0` | 每块存活水晶提供的护甲韧性 |
| `crystal.toughnessCap` | `8.0` | 水晶护盾的韧性上限 |

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

## 八、回退清单（哪条不想要就关哪个）

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

## 九、洞穴战斗 / 寻路 / 岩浆（0.4 追加）

### 洞穴站位（"贴上去挨揍"）

洞穴里不再用"嘴够到就停"的写法，而是由 `DragonGolemEntity.caveStandoff(...)` 算一个
**身体中心到目标中心**的安全距离：

- 高度：目标头顶 + `cave.attackHeight`（默认 4 格）；
- 水平：把该高度换算到"嘴够得着"的球面半径上（和喷息动画共用同一个半径）；
- 近战：目标实际攻击距离（原版 `2 × 目标宽度` 与 `forge:entity_reach` 取大）
  + 双方半宽 + 1 格缓冲；刚挨打后的 10 tick 再加 1.5 格；
- **上限**：`max(嘴前伸量 × 1.15 × standoffFactor, 近战安全距离) + 1.5`。
  防止在十几格宽的水池/房间里退到 15 格以外、直接退出视线（真机"看不见人、也不喷"）。

前摇 / 喷息 / 收尾阶段都会继续维持这个距离（能退就退），不再站桩把 20+60 tick 白送给近战。
**开火闸门只要求离开目标的实际近战范围**（`meleeSafeDistance`），不要求先退到完整站位；
所以观感是"边打边退"，而不是"一直往后跑、不打"。
想整体站得更远：调大 `cave.standoffFactor`；想贴得更近：调小它。

### 幽灵模式（"能穿墙却不认路"）

`cave.ghost = true` 时，**没有骑手且洞穴判定成立**的 AI 龙会主动 `noPhysics`：

- 移动层直接走直线：`DragonPathFollower` 不跑 A*，也不会因为"没路线"站桩；
- 飞行辅助不再把"身体嵌在方块里"当卡住（不触发脱困、不把龙往外推）；
- **不吃气泡柱 / 水流推动**（`isPushedByFluid` / `isAffectedByFluids` 在幽灵时返回 false）——
  否则灵魂沙气泡柱会把它一路顶到洞顶以上；
- 离开洞穴 / 关掉 `cave.ghost` 后自动恢复碰撞与自动脱困（有 5 秒滞回，防止洞口判定闪烁）；
- **竖直方向不再往上棘轮**：贴到天花板后不会每 tick 被 `ceilingFeetLimit` 的 `max(y)` 再抬 1.5 格；
  身体嵌在方块里时只允许下降/横移，`nudge` 也禁止再向上找出口。

### 寻路（A* 卡顿）

A* 不是每 tick 跑，而是：

- 直线（身体盒 + 液体）干净时**根本不跑 A***；
- 需要 A* 时，每只龙**最多每秒一次**（`pathfinding.repathTicks` 默认 20，代码下限也是 20）；
- 全服每 tick 最多 1 次搜索，单次最多 400 节点、搜索半径 16 格；
- 单次超过 10ms 就冷却 200 tick；
- **算不出路线就原地停**：goal 每 tick 只做一次廉价检查，真正的重算等到下一秒；
  找到路线后下一 tick 自动恢复。

日志：`[path] A* ok/null nodes=... ms=...`、`[path] noRoute → hold ...`。
如果某次 A* 的 `ms` 仍然很大，或者你只想要最省性能：`pathfinding.enabled = false`（退回直飞，
移动层的岩浆规避仍然生效）。

### 命令手杖（"右键很多次不能标记敌人"）

本家 `CommandWandItem` 对"目标也是 `AbstractGolemEntity`"走的是打开模式轮盘的分支，
所以在打"敌人本身是傀儡"的整合包里永远走不到 `resetTarget(target)`。
`DragonCommandWandHandler` 在 `EntityInteract`（实体交互之前）接管：

- 目标是自己的傀儡/自己的宠物 → 原样交给本家（轮盘/curios 不变）；
- 其它目标 → 扫描玩家周围 32 格、归属玩家、配置卡过滤通过的傀儡，逐个 `resetTarget(target)`，
  发和本家一样的 "N Golems will attack X" 提示，并取消事件；
- 日志：`[wand] command target=... markedGolems=...`（`debug.pathLog` 开启时）。

### 岩浆（"模型蹭到岩浆"）

- 洞穴战斗高度给岩浆单独留 `cave.lavaClearance`（默认 3 格；水只留 1.5 格）；
- 寻路节点按 `pathfinding.lavaMargin` 向外扩一圈查岩浆，路线拉直也要求"不沾液体"；
- 移动层再加一层前探：前方 2~3 格内有岩浆就侧移 + 抬升，幽灵模式也生效
  （幽灵不免疫岩浆伤害）。

### 水晶治疗光束与护盾（"参考末影龙的特效"）

- **光束**：不再自己撒"从水晶飞向龙的光点"，而是把附近一块存活水晶的
  `beamTarget` 指到龙的身体中心（每 4 tick 更新），让原版 `EndCrystalRenderer`
  直接画 `end_crystal_beam.png` —— 和末影龙拉水晶是同一条纹理。
  - 水晶原本已经有玩家设置的 beamTarget 时不抢；结束/龙死亡/收回时还原；
  - **不再叠加任何 `END_ROD` 脉冲/拖尾/落点粒子**。之前那层"方向性脉冲"
    在服务端卡顿追帧时会把几秒的粒子一次性补出来，真机就是满房间白色星点；
    现在治疗表现只有原版光束本身。
  - **光束只在龙和水晶互相有视线（`hasLineOfSight`）时绑定**，每 4 tick 复查一次。
    龙在洞穴幽灵模式里躲到墙后时，光束不会穿过整面墙指向一个玩家看不到龙的方向；
    没有视线时只保留回血，不画光束。
- **护盾**：附近每有一块存活水晶，给龙加 `crystal.armorPerCrystal` 点护甲 /
  `crystal.toughnessPerCrystal` 点韧性，按 `crystal.armorCap` / `crystal.toughnessCap` 封顶。
  这是常驻光环（和"受伤后才回血"无关），水晶拆掉后 1 秒内属性回落。
  日志：`[crystal] guard crystals=... armor=... toughness=...`；
  水晶实际数量变化时才会打。
- **水晶自爆免疫**（`crystal.explosionImmune`，默认 **关**）：
  只拦截"直接来源是 `EndCrystal` 的爆炸"（原版水晶被非爆炸伤害打掉时产生的自爆）。
  TNT、苦力怕、火球、其他爆炸仍然照常结算，继续走 L2C 的"爆炸免疫"或 MG 的"海绵升级"，
  所以**不是 blanket 爆炸免疫，不会和这两个升级冲突**。
  - **原版末影龙并不免疫水晶自爆**：`EnderDragon.onCrystalDestroyed` 在最近的水晶被拆时
    会调用 `hurt(head, explosion(crystal, player), 10.0F)`，固定吃 10 点；它的 `hurt`
    只是"非玩家来源的伤害不结算"，不是爆炸免疫。所以默认保持 `false`，让水晶继续是风险/收益。
  - 如果你要让龙傀儡把水晶当纯增益，把它设成 `true`；或以后做"水晶爆炸伤害上限/减伤"的中间档。
  - MG 海绵升级是 `1 - 等级 × 20%` 的爆炸减伤（5 级时到 0），不是回血/吸收；
    水晶自爆被单独免掉后，海绵不会"少吸一次血"，因为本来就没有回血。
  - 如果整合包里的"爆炸吸收"是**按爆炸伤害回血/充能**，那水晶免疫会让水晶爆炸不触发它；
    想保留这个联动就保持 `false`。

### 自动索敌与命令手杖锁定

- 巡场自动索敌只接受 `Enemy` 和**别人的 `AbstractGolemEntity`**；马、蝙蝠、试验假人、
  村民、自己的宠物不再被自动锁定（之前会丢下敌人去追动物，表现为"龙傀儡逃跑了"）。
- 命令手杖的 `forcedTarget` 不走这条过滤，并且**所有攻击 goal、技能调度、洞穴战斗高度**
  都统一读 `dragonCurrentTarget()`（先 forcedTarget，再本家目标槽）。
  这样"用手杖锁了下面的，却喷上面的"不会再出现两边目标不一致。
- 日志：`[target] ... forced=true/false patrolValid=...`。
- 龙息伤害现在对**每个候选目标**单独做"嘴 → 目标"的方块遮挡检查；
  隔着地板/墙角的上层目标不会再掉血（同一锥体里、视线通畅的目标仍然吃 AoE）。
