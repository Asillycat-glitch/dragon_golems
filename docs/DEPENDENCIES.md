# 依赖说明

这个 mod 只有一层：**傀儡装配（Modular Golems）**。龙不是自己写的一套实体，
而是本家的一个 `GolemType`，所以实体、部件、渲染、装备栏、标签都由本家现成的框架承担。

## 硬依赖

| 依赖 | 用途 |
|---|---|
| Minecraft 1.20.1 + Forge 47.x | |
| `modulargolems` ≥ 2.7.3 | `GolemType` / `GolemPart` / `AbstractGolemRenderer` / `SweepGolemEntity` / `EquipmentsMenu` / `modulargolems` 标签 |
| `l2library` ≥ 2.5.3 | `L2Registrate`（注册入口）、`MenuLayoutConfig`（装备界面渲染） |

运行时 `l2serial`、`mob_weapon_api`、`l2damagetracker` 这些由本家 jarjar 提供，不用单独装；
编译期必须能看见，所以 `build.gradle` 里列全了（jar 在 `libs/`）。

## 各文件依赖

| 文件 | 依赖的东西 |
|---|---|
| `dragon/DragonGolemItems.java` | Registrate（`L2Registrate`）、本家 `GolemType`/`GolemHolder`/`GolemPart` 与 `GolemTypes.TYPES` 注册表 |
| `dragon/DragonGolemEntity.java` | 本家 `SweepGolemEntity`（保留武器系统）、l2serial `@SerialClass`、原版飞行寻路；技能伤害那条出口还用了 l2damagetracker 的 `AttackEventHandler` / `CreateSourceEvent`（本家所有"改伤害源"的升级都挂在这个事件上，见 `docs/CONFIG.md` 第七节） |
| `dragon/DragonRetrieveHandler.java` | 本家 `RetrievalWandItem` 的 `ItemStack#interactLivingEntity` 入口、`MGConfig.COMMON.retrieveDistance`、l2library `RayTraceUtil`（那条"本家自己选得中就不抢"的判定）、原版 `ProjectileUtil` |
| `content/config/DragonGolemConfig.java` | 纯 Forge `ForgeConfigSpec`（**不经过 l2library**），注册路径照抄本家 `MGConfig` 的 `l2_configs/<modid>-common.toml` |
| `dragon/DragonGolemPartType.java` | 本家 `IGolemPart` / `GolemSlot`；物品预览变换要调客户端模型 |
| `dragon/DragonGolemType.java` | 本家 `GolemType` 的菜单 / 悬浮 / 图标三个扩展点 |
| `dragon/DragonGolemMenuControl.java` | 本家 `SweepGolemMenuControl`、`EquipmentsMenu.DOG_SLOTS` |
| `client/DragonGolemModel.java` | 本家 `IGolemModel`、原版末影龙网格（`ModelLayers.ENDER_DRAGON`） |
| `client/DragonGolemRenderer.java` | 本家 `AbstractGolemRenderer` |
| `client/DragonGolemScreenControl.java` | l2library `MenuLayoutConfig.ScreenRenderer`、本家 `GolemScreenControl` |
| `client/DragonGolemOverlayControl.java` | 本家 `GolemOverlayControl` / `GolemStatusOverlay` |
| `compat/jei/DragonGolemsJeiPlugin.java` | JEI 15 的 `IModPlugin` / `IRecipeRegistration`；**只在装了 JEI 时才会被加载**。另外读了本家 `GolemMaterialConfig` / `GolemPart`（为了把说明页登记到每个材料变体上） |

JEI 在 `build.gradle` 里是 `compileOnly`（jar 在 `libs/`）：编译要能看见它，运行时没有也不会出问题。

## 我们自己的扩展目录

从 `golems_arsenal` 拆出来时本仓只有 `dragon/` 一个包，现在多出来的几个都是后加的：

| 目录 | 干什么 |
|---|---|
| `network/` | 骑手按键（R/G/V）与自由飞行的发包/收包 —— 原版只同步"跳跃/潜行"两个键，其余按键服务端看不见 |
| `compat/jei/` | 只补本家没生成的 JEI 说明页（胚料 / 五个部件 / 成品），没装 JEI 也不会崩 |
| `init/` | 创造栏本体，以及往本家「傀儡装配 - 傀儡」页里补龙部件与成品的那段注入 |
| `mixin/` | 见下一节，**只有三个，而且都只注入本家** |

`golems_arsenal` 里那批 `tech/`、`base/`、以及二十多条针对别的附属的兼容代码别顺手抄回来。

## 我们开过的 mixin

| 类 | 注入目标（都是本家） | 干什么 |
|---|---|---|
| `GolemMaterialConfigMixin` | `GolemMaterialConfig.mayApply(GolemPart, ResourceLocation)` 的 HEAD | 别的附属用本家 `supportsDefaultAnd(...)` 生成的"复制式 `partLimitation` 清单"（8 个基础部件 + 它自己的部件）不再把龙部件排除：这种清单按"通用部件清单"放行 |
| `GolemCheckRideMixin` | `MetalGolemEntity` / `HumanoidGolemEntity` 的 `checkRide(LivingEntity)` 的 HEAD | 目标是龙时让傀儡上背当炮台（本家那两个覆写只认狗 / 马，见 `docs/MOUNT.md` 的「傀儡乘客（炮台）」） |
| `RideUpgradeMixin` | `RideUpgrade.fitsOn(GolemType)` 的 HEAD | 让本家的「坐骑升级」能装到龙身上（本家写死 `type == TYPE_DOG`）。开关 `mount.mountUpgradeOnDragon`，见 `docs/CONFIG.md` 第三节 |

**开 mixin 的规矩**（定死，别再放宽）：

1. **只注入本家 `dev.xkmc.modulargolems`**，必要时加原版类。**任何附属的类一个都不注入** —— 要兼容附属就读它的数据 / 标签（我们读 `GolemMaterialConfig`、往 `generic_parts` 标签里加部件就是这么做的）。上游 mgdp 那种"注入本家手杖 + 无条件骑任何目标"的写法不要学。
2. **三个都是 fail-soft**：注入点写 `require = 0`。本家以后换了签名，我们要的是"入口 / 修复悄悄失效 + 日志一行警告"，而不是所有玩家启动就崩。
3. **别注入原版类**：本仓没有 mixin 的 annotation processor + refmap（`build.gradle` 里没有、离线也补不了），注入原版成员需要 SRG 重映射，只有"注入本家类"这条路是不需要 refmap 的（本家自己的方法名不参与重映射）。这也是"属性上限不内置改 `RangedAttribute` 的 mixin"的原因之一，见 `docs/CONFIG.md` 第六节。
4. 加载开关集中在三处，缺一处 mixin 就不会被加载：`src/main/resources/dragon_golems.mixins.json`（清单）、`build.gradle` 里 jar 的 `MixinConfigs` 清单属性、`mods.toml` 的 `mixinConfigs=`。
5. 想确认生效，看 `logs/debug.log`：`Mixing ... into ...` 是注入成功；运行时另外还有两类 debug 行 —— `复制式部件清单按通用部件放行：<部件> ← <材料>`、`傀儡乘客上龙：<类型> 骑上 <类型>`。

> 「上龙」这件事有两半：**玩家**那条是 `dragon/DragonRideHandler`（Forge 的 `PlayerInteractEvent.EntityInteract`，**不开 mixin**）；**傀儡乘客**那条才是上面的 `GolemCheckRideMixin`。两半都记在 `docs/MOUNT.md`。

## 数据文件

`data/modulargolems/tags/**` 的命名空间必须是 `modulargolems`：这些文件是**往本家的标签里追加**
条目（数据包标签会合并），改成自己的命名空间就等于另建一份没人读的标签。

`data/dragon_golems/modulargolems_config/` 下的材料与部件配置则是自己的命名空间，
里面的 id 必须和 `DragonGolemItems` 的注册名、以及贴图文件名对得上。
`parts/dragon.json` 的 `magnifiers` 保持空表（全部 1.0），数值推导见 [CONFIG.md](CONFIG.md) 第六节：
生命上限那条不走数据，走配置项 `attributes.maxHealthCapFix`（默认开，内置版的 AttributeFix，
只放宽最大生命）。
