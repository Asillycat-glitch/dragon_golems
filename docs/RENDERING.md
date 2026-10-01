# 界面与渲染代码地图

这个 mod **一个自己的 Screen 都没有**：界面（槽位、底图、悬浮图标、菜单图标）全部挂在本家
（Modular Golems）预留的扩展点上；渲染这边只自己注册了两个 `EntityRenderer`
（`DragonGolemRenderer` / `DragonGolemFireballRenderer`），其余全部复用本家的渲染层。
所以要看清"哪块画面归谁管"，得按四条互不相干的路径分：
实体渲染、物品预览、界面、本家 GUI 里的悬浮 3D 预览（第四节）。

## 总览

| 我们的类 | 它管什么 | 本家在哪里调它 |
|---|---|---|
| `dragon/DragonGolemType` | 把菜单 / 悬浮 / 图标三个扩展点接出去 | `GolemType.menuControl` / `overlayControl` / `getMenuIcon` |
| `dragon/DragonGolemPartType` | 每个部件占哪个槽、各显示场景的预览倍率（与实测失败时的兜底）、部件说明文字 | `IGolemPart.getSlot` / `setupItemRender` / `getDesc` |
| `dragon/DragonGolemMenuControl` | 装备界面有几个槽、每个槽收什么 | `EquipmentsMenu` 构造时调 `fillMenu()` |
| `client/DragonGolemScreenControl` | 装备界面里的槽位底图 | `EquipmentsScreen.renderBg` → `ctrl.getScreenProvider()` |
| `client/DragonGolemOverlayControl` | 鼠标悬浮查看傀儡时的小图标 | `GolemStatusOverlay$GolemEquipmentTooltip` → `GolemType.overlayControl` |
| `client/DragonGolemRenderer` | 实体渲染的入口（模型 + 部件表 + 阴影体积 + GUI 悬浮预览的额外缩放） | 注册时由 Registrate 交给 `EntityRenderers` |
| `client/DragonGolemModel` | 逐部件的网格、贴图解析、物品预览摆位（实测自动）、动画与 GUI 预览倍率 | 本家渲染层 / `GolemBEWLR` |
| `client/DragonGolemFireballRenderer` | 龙弹：照抄原版末影龙火球（同一张贴图、方块光 15、显示 2 倍） | 我们自己的 `EntityRenderer` 注册 |
| `init/ModCreativeTabs` | 创造栏「巨龙傀儡」那一页（图标 = 身体部件）+ 往本家 `modulargolems:golems` 页补我们的物品 | 我们自己的 `DeferredRegister<CreativeModeTab>` + Forge 的 `BuildCreativeModeTabContentsEvent` |

---

## 一、实体渲染

用在：世界里的龙、装备界面里那个 3D 预览、物品栏里"带数据"的成品。

```
本家 AbstractGolemRenderer.render(...)          ← 只有实体渲染才走这里
  ├─ setupRotations / scale（本体变换）
  │    └─ 我们的 DragonGolemRenderer.scale 覆写：本家那层之后，再按姿势矩阵判断"是不是 GUI 预览"
  │       （第四节；判据是姿势的线性缩放 > 10.0）
  ├─ GolemDefaultLayer.render(...)              ← 构造函数里挂上的"按部件 + 按材料"图层
  │    └─ 每个部件单独调 IGolemModel.renderToBufferInternal(part, pose, vc, ...)
  │         └─ 我们的 DragonGolemModel.renderToBufferInternal
  │              ├─ 贴图 = getTextureLocationInternal(材料 id) → textures/entity/dragon/<材料>.png
  │              └─ 本家会再问一次 "<材料>_emissive"，资源在就多画一遍发光层
  └─ 动画走 EntityModel.setupAnim ← 我们的 DragonGolemModel.setupAnim
       （拍翅 / 下颌 / 脖子 / 尾巴 / 服务端同步过来的机体俯仰与侧倾）
```

- `DragonGolemRenderer` 干三件事：把 `DragonGolemModel` 与 `DragonGolemPartType::values` 交给本家，
  给个阴影体积（`1.2F`），再在 `scale()` 里补 GUI 悬浮预览的缩放。
- `DragonGolemModel.renderToBufferInternal(part, ...)` 是**"哪个部件画哪些 ModelPart"的唯一开关**：
  `HEAD` 画头 + 5 段前颈；`BODY` 只画重心那一块；`TAIL` 画 12 段尾巴；`WINGS` 一次画左右两翼；`LEG` 一次画四条腿。
  （量物品预览包围盒的 `measurePart(...)` 和这里的分派是一一对应的，改了一边别忘了另一边。）
- `DragonGolemModel.setupAnim(...)` 只有这条路径会走（物品预览不走），负责摆姿势，并把"物品预览"的
  静态标记（`previewFactor` / `holderPreview`）复位（见下一节）。
- 俯仰与侧倾不是客户端自己算的：`DragonGolemEntity.tickBodyPitch()` 在服务端插值后同步
  （`DATA_BODY_PITCH` / `DATA_BODY_ROLL`），模型和子碰撞箱读同一个值。
- 贴图回退链：`dragon/<材料>.png` → `dragon/base.png` → `dragon/enderdragon.png`（`FALLBACK_LEGACY`，
  这个类路径下没有资源，实际会跳过）→ 原版末影龙贴图。现有贴图是 base / copper / gold / iron /
  netherite / netherite_emissive / sculk / sculk_emissive 八张。
  但以 `_emissive` 结尾的材料**绝不走兜底**——否则整条龙会被当成发光层再画一遍。

### 尺寸的三个旋钮（别混）

| 旋钮 | 位置 | 影响范围 |
|---|---|---|
| `MODEL_SCALE` / `MODEL_LIFT` | `client/DragonGolemModel` | **只管模型**：网格画多大、抬多高。判定箱、AI、载客都不看它 |
| `.sized(w, h)` | `dragon/DragonGolemItems` 的 `ENTITY` | **只管判定箱的"一倍"基准**（现在是 `.sized(2.6F, 2.2F)`，宽 × 高）：碰撞、贴墙、站位 |
| `GOLEM_SIZE` 属性 | `dragon/DragonGolemEntity.createAttributes`（默认 **5.0**） | **两个一起**：`getScale() = 当前值 / 默认值`，渲染（`AbstractGolemRenderer.scale`）和判定箱（`LivingEntity.getDimensions().scale(getScale())`）都乘它 |

要点：

- `getScale()` 是**倍率**，默认值只是"一倍"的基准点。所以把默认 `GOLEM_SIZE` 从 1 改成 5，
  只要当前值也是 5，**画面与判定箱一点变化都没有**；变的是升级/重铸的**相对幅度**：
  本家 `size_up` 每级 +0.5 → 基准 1 时 +50%，基准 5 时只有 +10%（本家自己的金属/类人/狗用的是
  3 / 2.5 / 1 这种抽象档位）。
- 它还会进 `maxUpStep() * getScale()`、行走动画速度，以及（以后若搬狗的载客公式）`min(size*2-1, 3)`。
- 重铸也参与：`AbstractGolemEntity.getScaleImpl() = GOLEM_SIZE × ((重铸基数 − 重铸次数) / 重铸基数)^(1/3)`，
  而重铸基数 = `getReforgeBase()` = 五个部件的铁砧消耗之和（`GolemPart.count`）。龙现在是
  双翼 8 + 头 12 + 身体 12 + 尾巴 8 + 四肢 8 = **48**，所以每次重铸缩 `((48−n)/48)^(1/3)`。
  （注意 `DragonGolemItems` 的类注释里还写着"头 3、身体 6、每只翼 1、四肢 4 = 15"，那是旧数字，代码里是 48。）
- `MODEL_LIFT / MODEL_SCALE` 现在是 1.36 / 1.7 = 0.8（= 原点到脚底的模型单位数）。改 `MODEL_SCALE` 时按同一比例改
  `MODEL_LIFT`，就纯粹是"整体等比缩小"，龙站的位置和姿态都不动。
- 物品预览那一套是**另一组旋钮**，和上面三个无关：部件用 `PREVIEW_TARGET_PX`（按部件分档的数组）、
  成品用 `HOLDER_TARGET_PX`，居中/缩放由 `measurePreview()` 实测自动算；本家 GUI 里那条悬浮预览另有
  `GUI_PREVIEW_TARGET_BLOCKS`（见第四节）。改模型大小不会动背包/手持图标。

## 二、物品预览

用在：背包 / 手持 / 展示框里的部件与成品。这条路 **没有 `setupAnim`**，本家直接调
`renderToBufferInternal`，所以姿势、居中、缩放全部由我们自己在渲染前设好：

```
本家 GolemBEWLR.renderByItem（BEWLRHandle：stack / ctx / pose / buffer / light / overlay）
  ├─ 部件（IGolemPartItem → GolemPart）：
  │    part.setupItemRender(pose, ctx, part)        ← 我们：按显示场景给"预览倍率"
  │        第一/第三人称手 = HAND_FACTOR(0.6)、HEAD = HEAD_FACTOR(0.8)、其它 = 1.0
  │    └─ renderToBufferInternal → previewFactor > 0 走预览分支：
  │         ① this.pose(DISPLAY_TIME, ...) 用预览专用状态重摆姿势
  │         ② measurePreview()（全局只量一次）→ AUTO_PIVOT[idx] / AUTO_SCALE[idx]
  │         ③ translate(0.5, 0.5, 0.5)                      ← 抵消原版 ItemRenderer 的 translate(-0.5,-0.5,-0.5)
  │         ④ mulPose(ZP, PREVIEW_PITCH) → mulPose(YP, PREVIEW_YAW) → scale(-s, -s, -s)
  │         ⑤ translate(-pivot/16)                          ← pivot 是模型像素，PoseStack 是格
  ├─ GolemHolder，且 NBT 里没有 golem_entity / golem_icon / golem_equipments：
  │    values()[0].setupItemRender(pose, ctx, null)    ← 注意：部件参数是 null！
  │    └─ 我们的判空分支：translate(0.5,0.5,0.5) → HOLDER_PITCH / HOLDER_YAW
  │       → scale(s·1, s·-1, s·-1)（HOLDER_FLIP_X/Y/Z）→ translate(holderCenterOffset())
  │       → 再按材料表逐件 renderToBufferInternal（材料表缺项才退到 GolemMaterial.EMPTY → base.png）
  └─ GolemHolder，且 NBT 里有 `golem_entity` / `golem_icon` / `golem_equipments` 之一：
       ClientHolderManager.getEntityForDisplay 造"展示用实体"（有 `golem_entity` 走 createForDisplay，
       **没有**才现造一个并打上 `ClientOnly` 标签）
       → renderEntity 里 translate(0, 1.501, 0) + scale(1, -1, -1) → 交给真正的实体渲染器
       → 所以这条路上会走 setupAnim：带 `ClientOnly` 标签的（纯图标）被钉成静态姿势，
         带实体数据的照常按它自己的状态摆；两种情况都会把 previewFactor / holderPreview 复位
```

四个必须记住的点：

1. **`setupItemRender` 会被传 `null` 部件**，而且两条路都会：`GolemBEWLR.render(handle, holder)` 与
   `GolemBEWLR.renderEntity(...)` 都是 `values()[0].setupItemRender(pose, ctx, null)`。
   我们那条判空分支不判空就会 NPE —— 创造栏一点就崩。
2. **预览倍率是 static 字段**（`DragonGolemModel.setPreviewFactor`），因为这条路上只有静态上下文。
   所以 `setupAnim` 必须把它复位成 `0`（成品另有 `setHolderPreview(false)`），
   否则"先看物品、再看实体"会让实体的缩放/姿态错乱。
3. **模型是"Y 向下"画的**（网格借用末影龙），部件预览里要 `scale(-s, -s, -s)` 才是正的。
   成品那条路的轴翻转是**另外一组旋钮**：`HOLDER_FLIP_X = 1` / `HOLDER_FLIP_Y = -1` /
   `HOLDER_FLIP_Z = -1`（也就是 `(s, -s, -s)`），而且它后面接的变换和部件预览不同
   （逐件分支接的是模型的 `translate(0,-MODEL_LIFT,0)` + `scale(MODEL_SCALE)`，
   `renderEntity` 分支接的是 MG 的 `scale(1,-1,-1)` + `translate(0,1.501,0)` 和渲染器的 `scale(-1,-1,1)`）。
   看着镜像/上下反了就改这三个数，别去动部件预览那条。
4. **模型中心必须落在 (0.5, 0.5, 0.5)**。原版 `ItemRenderer.render` 的顺序是
   `handleCameraTransforms(...)` → `translate(-0.5, -0.5, -0.5)` → `IClientItemExtensions.getCustomRenderer()`
   （也就是 `GolemBEWLR.renderByItem`）——**那一步减 0.5 是在我们之前做的**，它假设自定义模型的中心
   在 (0.5,0.5,0.5)。我们的模型中心在原点，所以部件预览和成品预览都先在**最外层**补一个
   `translate(0.5, 0.5, 0.5)`；漏掉就是"图标整体偏左下各 8 像素（正好半格）"。

### 预览摆位是实测的，不是手填的

- `measurePreview()` 在构造里跑一次：用假的 `VertexConsumer`（`BoundRecorder`）按预览姿势把
  "该部件真正会渲染的那批 ModelPart"画一遍，收顶点算包围盒 —— 中心存 `AUTO_PIVOT[部件]`、
  跨度换算成 `AUTO_SCALE[部件]`；整条龙合起来算 `holderCenterPx` / `holderScale`。
  头 + 5 段前颈、12 节尾巴（同一个 `neck` 部件被摆多次）也都会算进去。
- 目标跨度：`PREVIEW_TARGET_PX = {30, 30, 22, 22, 22}`（索引 = `DragonGolemPartType.ordinal()`：
  WINGS / HEAD / BODY / TAIL / LEG）；`HOLDER_TARGET_PX = 30`；表里取不到时用
  `PREVIEW_TARGET_FALLBACK_PX = 22`。单位是**模型像素**（1 格 = 16）。
- 所以"某个部件图标偏了/太大"不用再去填表：形状改了实测值会自动跟着走；
  要整体放大缩小只调 `PREVIEW_TARGET_PX` / `HOLDER_TARGET_PX`。
- `DragonGolemPartType.SCALE_HOLDER`（0.2）现在只是**实测失败时的兜底**，`HAND_FACTOR` / `HEAD_FACTOR`
  仍然有效（它们是在实测倍率之上再乘的显示场景系数）。
- 成品那条路的中心偏移不能直接用 `holderCenterPx/16`：外层平移写在模型的
  `translate(0,-MODEL_LIFT,0)` + `scale(MODEL_SCALE)` **之外**，所以要用
  `holderCenterOffset()`（把 `MODEL_LIFT` / `MODEL_SCALE` 一起算进去）。

### 物品模型 json

- `assets/dragom_golems/models/item/` 下的六个（五个部件 + `dragon_golem_holder`）都是
  `"parent": "minecraft:builtin/entity"`，只给一个 `particle` 贴图占位；显示变换全部由
  `setupItemRender` 在代码里做。
- `dragon_golem_template.json` 是**唯一的特例**：`item/generated` + 借用本家胚料贴图，并手写了全套
  `display`（`gui` 缩放 1.5、`firstperson` 1.7、`thirdperson` 4.25，照抄暮色巨型锭那一套）。
  它没有 BEWLR，所以只能在 json 里调。

## 三、界面

| 看到的画面 | 我们的类 | 本家在哪调 |
|---|---|---|
| 装备界面（打开龙身上的 GUI） | `dragon/DragonGolemMenuControl` | `GolemType.menuControl(menu, golem)`；`fillMenu()` 决定槽位与收物规则 |
| 装备界面的槽位底图 | `client/DragonGolemScreenControl` | `EquipmentsScreen.renderBg` → `menu.ctrl.getScreenProvider()` → `render(...)` |
| 悬浮查看傀儡时的装备小图标 | `client/DragonGolemOverlayControl` | `GolemStatusOverlay$GolemEquipmentTooltip` → `GolemType.overlayControl(golem)` → `getWidth/getHeight/renderImage` |
| 装备界面的菜单图标 | `dragon/DragonGolemType.getMenuIcon` | 本家标签页取用 |
| 创造栏那一页 | `init/ModCreativeTabs` | 我们自己的创造栏注册；另外用 `BuildCreativeModeTabContentsEvent` 往本家 `modulargolems:golems` 页补上五个部件（本体 + 每种材料一份）与成品（每种材料一条） |

细节：

- **槽名两边必须一致**：`fillMenu()` 里 `addSlot("chest", ...)` / `addSlot("legs", ...)`，
  `DragonGolemScreenControl` 里就 `renderer.draw(g, "chest", "slot", -1, -1)`，悬浮图标里也是
  `renderSlot(..., "altas_helmet")` / `slotbg_dog_armor`（这两个是本家的贴图名，直接沿用犬型那套）。
  名字对不上 = 框画了但没槽，或者反过来。
- 装备界面里龙本体的 3D 预览走的是本家的 `EquipmentsScreen.renderPreview`：它不再按 `instanceof` 取数，
  而是问 `AbstractGolemEntity.getPreviewScale()`（默认 **18**，金属覆写成 20 / 类人 24 / 犬 32），
  再除以 `golem.getScale()`。龙没有覆写它 → 落进默认的 18。置换/分解界面
  （`GolemDisinegrateScreen.renderPreview`）同样用 `getPreviewScale()`，但不除 `getScale()`。
  也就是说装备界面那条除完还是"每格 18 像素"，置换/分解那条是 `18 × 展示实体的 getScale`
  （默认 1.0）。对我们这条 26 格长的龙来说两者都糊满界面 —— 修正见第四节。
- 想给槽位加悬浮说明，覆写 `GolemScreenControl.addSlotTooltip(Slot)`（父类默认空实现）。
- `getScreenProvider()` 每次开界面都会 new 一个 ScreenControl（本家就是这么用的），别在里面缓存状态。

## 四、本家 GUI 里那条悬浮 3D 预览（置换/分解、装备界面）

本家的置换/分解界面（`GolemDisinegrateScreen.renderPreview`）和装备界面
（`EquipmentsScreen.renderPreview`）都用原版 `InventoryScreen.renderEntityInInventoryFollowsAngle`
画一个会跟着鼠标转的 3D 预览。它给的比例是"**每格多少像素**"（`getPreviewScale()`：默认 18 /
金属 20 / 类人 24 / 犬 32），而那条龙有 **26.6 格长** → `26.6 × 18 ≈ 479 px`，直接把整个界面糊满。

所以我们在 `client/DragonGolemRenderer.scale(...)` 里补了一个额外倍率：

```
DragonGolemRenderer.scale(entity, pose, partialTick)
  ├─ super.scale(...)                       ← 本家：pose.scale(getScale(), getScale(), getScale())
  └─ if (isGuiPreviewPose(pose))            ← 判据：姿势矩阵的线性缩放 > GUI_PREVIEW_POSE_SCALE = 10.0
        pose.scale(s, s, s)                 ← s = DragonGolemModel.guiPreviewScale()
```

- **判据怎么来的**：原版 `renderEntityInInventory` 会对整个姿势栈做 `scale(-scale, scale, scale)`，
  也就是线性缩放 = 那个"每格像素"值。`isGuiPreviewPose` 取矩阵 **X 基向量的模长**当缩放估计
  （平移不影响它、旋转也不改变它的模长），三条路分别是：
  世界实体 = `getScale()`（默认 1.0）、物品图标那条路自己在 `setupItemRender` 里缩得极小（≈ 0.07）、
  GUI 预览 18~32（本家 `getPreviewScale()`：默认 18 / 金属 20 / 类人 24 / 犬 32）。
- **为什么不能按 `ClientOnly` 标签判**：本家 `ClientHolderManager.getEntityForDisplayInternal` 只在
  **没有 `golem_entity`** 那条分支里 `addTag("ClientOnly")`；带实体数据的成品走的是
  `GolemType.createForDisplay(...)`，**没有标签**；装备界面用的更是世界上那个真实体
  （`EquipmentsMenu.golem`）。而且 `AbstractGolemEntity.getScale()` 一看到 `ClientOnly`
  就直接返回 1 —— 标签在这条路上既不全也不唯一，只能当"物品栏假实体"的记号用
  （我们自己的 `DragonGolemModel.DISPLAY_TAG` 就是拿它来把动画钉成静态的）。
- **倍率怎么算的**：`measurePreview()` 量出整条龙的世界跨度
  （`maxSpanPx / 16 × MODEL_SCALE` ≈ 26.6 格），`guiPreviewScale() = GUI_PREVIEW_TARGET_BLOCKS / 世界跨度`
  = 5.0 / 26.6 ≈ 0.19。基准是**长度**：龙又长又扁（26.6 格长、约 4 格高），按长度缩完高度只剩
  目标格数的 15% 左右，所以 5.0 看着才等于"一条正常大小的龙"（约 90 × 14 px）。
  嫌大改小、嫌小改大，旋钮就是 `GUI_PREVIEW_TARGET_BLOCKS`（到 6 以上会开始压到面板边缘）。
- **为什么缩在 `scale()` 里而不是缩模型**：渲染顺序是
  `LivingEntityRenderer` 的 `scale(-1,-1,1)` → `AbstractGolemRenderer.scale` → `translate(0,-1.501,0)`，
  把倍率塞进 `scale` 这一步，后面那个 -1.501 会跟着一起缩，龙的"脚底"仍然钉在面板坐标上；
  改 `MODEL_SCALE` 就得同时改 `MODEL_LIFT`，而且会连世界里的龙一起改。
- **阈值为什么是 10 而不是 2**：世界那条路也不是 1，而是 `getScale()` —— 龙自己的体型升级最高 2 级
  （`GOLEM_SIZE` 6.0 → 1.2 倍）无害，但**第三方那种 +300% 体型的"泰坦"类升级**能把 `getScale()`
  顶到 4（`DragonGolemEntity.bodyScale()` 甚至夹到 8）。早期取 2 的时候，世界里的泰坦龙会被误判成
  GUI 预览、白白多缩一次。而 GUI 那条路最低也有 18，所以 10 把两群人彻底分开：
  世界里 `getScale()` ≤ 8，GUI ≥ 18。

## 五、想改的时候去哪儿

| 想改什么 | 位置 |
|---|---|
| 龙**看起来**的大小（不动判定箱） | `client/DragonGolemModel` 顶部 `MODEL_SCALE`（1.7；抬高要按 0.8 比例跟着改 `MODEL_LIFT` = 1.36） |
| "一倍"判定箱（碰撞体积） | `dragon/DragonGolemItems` 里 `ENTITY` 的 `.sized(2.6F, 2.2F)` |
| 尺寸基准档位 / 升级与重铸的缩放幅度 | `dragon/DragonGolemEntity.createAttributes` 的 `GOLEM_SIZE`（默认 5.0） |
| 整条龙的朝向 | `client/DragonGolemModel` 顶部 `MODEL_YAW`（0） |
| 物品预览里各部件的居中 / 大小 | 已改为**实测自动**（`DragonGolemModel.measurePreview()` 量包围盒 → `AUTO_PIVOT` / `AUTO_SCALE`）；总大小只调 `PREVIEW_TARGET_PX`（部件，按 ordinal 分档）/ `HOLDER_TARGET_PX`（成品） |
| 物品预览里各部件的角度 | 同文件 `PREVIEW_YAW`（-60）/ `PREVIEW_PITCH`（35） |
| 手持 / 头盔位再缩小 | `dragon/DragonGolemPartType` 的 `HAND_FACTOR` / `HEAD_FACTOR` |
| 成品图标的姿态与轴翻转 | 同文件 `HOLDER_PITCH` / `HOLDER_YAW` / `HOLDER_FLIP_X/Y/Z`（`SCALE_HOLDER` 只是实测失败时的兜底） |
| 本家 GUI（置换/分解、装备）里悬浮预览的大小 | `client/DragonGolemModel` 的 `GUI_PREVIEW_TARGET_BLOCKS`（判据与阈值在 `client/DragonGolemRenderer`） |
| 拍翅频率、脖子与尾巴的摆动、张嘴 | `client/DragonGolemModel.pose(...)`（下颌用 `JAW_OPEN_BREATH` / `JAW_LERP`） |
| 贴图命名与回退 | `client/DragonGolemModel` 的 `TEXTURE_PREFIX` 与三个 `FALLBACK_*` |
| 装备界面的槽位与底图 | `dragon/DragonGolemMenuControl.fillMenu()` + `client/DragonGolemScreenControl.render(...)` |
| 悬浮图标的大小 | `client/DragonGolemOverlayControl.getWidth/getHeight`（18 × 38） |
| 龙弹图标的大小 | `client/DragonGolemFireballRenderer.SCALE`（2.0，原版值） |
| 部件/成品的物品模型 | `assets/dragom_golems/models/item/*.json`（五个部件 + 成品这 6 个都是 `minecraft:builtin/entity`）；胚料模板单独一个 `dragon_golem_template.json`（`item/generated` + 全套 `display`） |
