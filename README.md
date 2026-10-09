# Dragon Golems（巨龙傀儡）

给「傀儡装配」（Modular Golems）加的一条**龙形傀儡**：一个傀儡种类、五个部件、一个成品、一个胚料、
五个装材料的部位配置，外加自己的创造栏、装备界面、JEI 说明页与图标预览。
从 `golems_arsenal`（傀儡军械库）里拆出来单独维护 —— 武器 / 科技 / 铁魔法那些留在那边，这边一概没有。

> 注册 id 是 `dragon_golems`，显示名是 **Dragon Golems**。
> 早期这个 id 误拼成了 `dragom_golems`（漏了 `n`），现已修正；
> 注意 id 变过，旧存档与旧配置里引用 `dragom_golems:...` 的实体、配方与标签都会失效。

## 这条龙会干什么

**通常攻击行为：锥形龙息**（无冷却，一般都是使用龙息攻击）

- 判定是从嘴部往前的一条锥体，长度随体型放大（基准 24 格，泰坦体型约 45 格）。
- 粒子、伤害类型与附带效果由**身体部件**的材料决定：

| 身体材料 | 龙息表现 | 附带效果 |
|---|---|---|
| 普通（铁 / 金 / 铜 …） | 火焰 | 着火 3 秒 |
| 下界合金 | 灵魂火 | 着火 5 秒 + 凋零 |
| 幽匿 | 音波（用原版 `sonic_boom` 做伤害：穿护甲与附魔，但吃抗性药水） | 黑暗 + 虚弱 |

**其它攻击行为**（按可用性加权随机挑，不是固定循环）

- **俯冲**：拉升后俯冲掠过，接触盒随体型往下铺；独立 30 秒冷却。
- **龙弹**：吐一颗带残留云的火球；与音爆**共享 15 秒冷却**（谁先满足条件谁放）。
- **音爆**：线状穿透，沿途目标全中，用本家的 `echo_attack`（真伤）。**只有幽匿身体的龙有这一招** ——
  所以幽匿龙 = 音波龙，其它材料回到「俯冲 / 龙息 / 龙弹」三招。
  （原来L2自己的echo这么超模吗...呃呃，根据反响会改成原版回响，不用担心，群攻不会受影响）

**所有几何都跟着体型走**：判定箱、悬停高度、转向速率、漫游与归位距离、俯冲的起止高度与接触盒、
音爆光束长度、环绕半径、龙息锥长。极端情况如：「泰坦升级」（+300% 体型），会导致龙模型不可见和
龙傀儡向后喷龙息和龙息弹（MC不渲染背后的生物这一块）可以使用渲染修复mod

**和本家的接口**

- 和大型/犬型/人形傀儡一样，可以自由组合龙傀儡部件和安装升级，所有渲染类升级出现问题都是特性（认真）。
- 本来设想是在「傀儡装配 - 傀儡」页补上「五个部件 × 每种材料」
  与「每种材料一条纯部件龙」可是发现会有其它未注册的材料也拥有龙（无贴图，无法放置）因此作罢，全部放到了龙傀儡的注册栏。
- 和大型/犬型/人形傀儡一样，置换/分解台与装备界面里那条悬浮 3D 预览会自动缩放，不会糊满界面（只是会显得有些小）。
- JEI 里有胚料、部件、成品的说明页。

## 依赖

| 依赖 | 版本 | 说明 |
|---|---|---|
| Minecraft | 1.20.1 | |
| Forge | 47.x（开发用 47.4.20） | |
| 傀儡装配 Modular Golems | ≥ 2.7.3 | **必装**，龙是它的一个 `GolemType` |
| l2library | ≥ 2.5.3 | `L2Registrate` 来自这里 |
| Registrate | MC1.20-1.3.11 | 编译期依赖，运行时由本家提供 |

## 安装

1.  **Modular Golems ≥ 2.7.3** 和 **l2library ≥ 2.5.3**。
2. 把 `dragon_golems-<版本>.jar` 丢进 `mods/`。
3. **不要和 `golems_arsenal` 一起装**：两边都注册了一条龙，创造栏会看到两份（已修复）

## 构建

```
./gradlew build
```

产物在 `build/libs/dragon_golems-<版本>.jar`。

## 代码结构

```
src/main/java/a_silly_cat/dragon_golems/
  Dragon_golems.java              主类（@Mod）：MODID / id() / 初始化注册 / 配置注册
  init/ModCreativeTabs.java       自己的创造栏；另外往本家的傀儡页补物品
  compat/jei/                     JEI 说明页（JEI 不在也能正常玩）
  dragon/                         服务端与两端共用：实体 / 种类 / 部件枚举 / 装备栏 / AI / 全部注册
  dragon/DragonRetrieveHandler    回收手杖的"认子箱"瞄准辅助（Forge 事件，不改本家代码）
  content/config/                 TOML 配置（DragonGolemConfig）+ 数据包身体主题表（DragonBodyConfig）
  mixin/                          往本家类里补三个口子：材料表过滤 / 傀儡上龙 / 坐骑升级可装龙
  client/                         仅客户端：模型 / 渲染器 / 装备界面底图 / 悬浮图标 / 预览摆位

src/main/resources/
  assets/dragon_golems/           物品模型、lang、龙的贴图
  data/dragon_golems/             材料与部件配置、7 条配方
  data/modulargolems/tags/        往本家的标签里追加（命名空间保持 modulargolems 不动）

docs/
  CONFIG.md                       ★ 配置文件在哪 + 龙息递减/坐骑升级/泰坦压乘客/选定与回收/属性上限/技能挂载点
  DEPENDENCIES.md                 依赖与各文件用到了什么
  JEI.md                          JEI 里哪些页是本家白送的、我们补了什么
  MOUNT.md                        升级/坐骑/载客的反查结果：哪条升级被挡、龙最多载几个、怎么落地
  RENDERING.md                    界面与渲染代码地图：哪块画面归哪个文件管
  DEV-RUN.md                      开发环境为什么跑不起来、run/mods 里那三个 jar 是干嘛的
```

## 配置

```
config/l2_configs/dragon_golems-common.toml
```

和傀儡装配、l2library 的配置**同一个目录**（本家就是这么放的）。可调项：
龙息对同一目标的递减开关与步长、坐骑升级能否装到龙上、准星拾取半径、
技能升级的两个挂载点（俯冲触发"近战族"地震/跳劈、是否保留本家的 `*AttackGoal`）、
属性超上限日志、以及**内置属性修复**（等价于 AttributeFix，只放宽最大生命，默认开 ——
幽匿龙裸装 1000 血已经是原版 1024 上限的 97.7%）。
每一条的来龙去脉（含"为什么只放宽最大生命"）见
[`docs/CONFIG.md`](docs/CONFIG.md)。

## 尺寸与预览：几个旋钮各管一段

别混着调，细节都在 `docs/RENDERING.md`：

- **世界里的碰撞箱** → `dragon/DragonGolemItems` 的 `ENTITY`，现在 `.sized(2.6F, 2.2F)`。
- **看起来多大** → `client/DragonGolemModel` 的 `MODEL_SCALE`（现在 1.7），抬高量 `MODEL_LIFT`（1.36）
  按 0.8 的比例跟着改，就只是等比缩放、站位不变。
- **尺寸档位** → `dragon/DragonGolemEntity.createAttributes` 的 `GOLEM_SIZE`，现在 **5.0**。
  本家的 `getScale()` 是"当前值 / 默认值"的**倍率**，所以默认值本身不改变大小；
  基准取 5 只是让本家 `size_up` 的每级 +0.5 从"放大 50%"变成"放大 10%"。
  这条属性还会影响判定箱、台阶高度、重铸缩放，以及载客上限的计算（见 `docs/MOUNT.md`）。
- **各种图标与预览** → `client/DragonGolemModel` 顶部的 `PREVIEW_TARGET_PX`（五个部件的背包图标，
  按部件分档）、`HOLDER_TARGET_PX`（成品图标）、`GUI_PREVIEW_TARGET_BLOCKS`（本家界面里那条悬浮预览）。

## 移植说明

从 `golems_arsenal` 拆出来时只做了命名空间替换，游戏逻辑一行没动：

| 原（golems_arsenal） | 现（dragon_golems） |
|---|---|
| 包名 `a_silly_cat.golems_arsenal` | `a_silly_cat.dragon_golems` |
| `Golems_arsenal.MODID` / `.id(...)` | `Dragon_golems.MODID` / `.id(...)` |
| `assets/golems_arsenal/...`、`data/golems_arsenal/...` | `assets/dragon_golems/...`、`data/dragon_golems/...` |
| json 里的 `golems_arsenal:xxx` | `dragon_golems:xxx` |
| `data/modulargolems/tags/...` 的命名空间 | **不变**，只换里面的物品/实体 id |
| 创造栏挂在 `golems_arsenal` 的标签页里 | 新建独立标签页 `itemGroup.dragon_golems` |

物品的注册名（`dragon_golem_head` 等）、材料 id（`iron` / `gold` / …）和贴图文件名都保持原样，
所以材料/配方/贴图那套对应关系不需要重新推一遍。移植前的原件只在本地留作对照，不进版本库。

## 还没做的

1. **胚料的价格是暂时的**。现在一共 7 条配方：`7 个小傀儡胚料 + 钻石 + 铁块 → 大胚料`（合成台，
   3×3 里其余 7 格全是本家的小胚料）、「大胚料 → 五个部件」（切石机，一块切一个）、
   「五部件 → 成品」（装配台）。
   数值待定 —— 现在这个档位是"明显比金属傀儡贵、但主要开销仍在铸材料"。
2. **装备栏是犬型两格**（头盔位 + 胸甲位），没有主手与远程槽。
3. **载客：玩家与傀儡都能上，一条龙只收一个玩家**。座位、驾驶、骑手指令 R/G/V 都已落地；
   本家的「坐骑升级」现在也能装到龙身上（`RideUpgradeMixin`，装上去 = 转成被动坐骑）。
   详见 [MOUNT.md](docs/MOUNT.md) 第七节。
4. **子碰撞箱与模型在竖直方向上未必完全对齐**（旧文里记的"整体偏高"）。没有可靠的理论值，
   只能开 F3 + B 实测；现在由拾取半径与回收手杖瞄准辅助兜住"点不中"的体感，
   见 [CONFIG.md](docs/CONFIG.md) 第五节。

## 许可

MIT，见 [`LICENSE`](LICENSE)。
