# 开发环境运行（runClient / runServer）

结论先说：**目前开发环境跑不起来，卡在 l2library 自己的 mixin 上，与本 mod 无关。**
本 mod 的验证方式是「构建成 jar → 丢进整合包测试」，这也是 `golems_arsenal` 一直以来的做法
（那个仓库里同样没有 `run/` 目录）。

## 为什么跑不起来

`mixin.env.remapRefMap` 那几个开关已经加进 `build.gradle` 了（不加的话，依赖里的 mixin
连目标方法都找不到，会在 `l2library.mixins.json:EntityMixin` 就炸）。加上之后仍然会死在下一关：

```
Mixin apply failed l2library.mixins.json:AbstractArrowMixin -> net.minecraft.world.entity.projectile.AbstractArrow
@Shadow field f_36697_ was not located in the target class ... Using refmap l2library.refmap.json
```

`AbstractArrowMixin` 的影子字段在源码里就是拿 SRG 名（`f_36697_`）写的，而它的 refmap
里没有这条字段映射，所以只有**生产环境（成员名就是 SRG）**能命中；开发环境用的是 official 名，
必然找不到。这是上游 jar 的写法问题，改不了，除非 l2library 自己发一版开发友好的 jar。

## `run/mods` 里为什么放这三个 jar

开发环境下依赖分两部分：`build.gradle` 里 `implementation` 的那批会进模块路径，
而 `run/mods` 里的才是"作为一个 mod 被加载"。

| 文件 | 为什么是它 |
|---|---|
| `modulargolems-2.7.3.jar` | 本家。与整合包里那份完全相同 |
| `l2library-2.5.3-slim.jar` | **必须用 slim 版**。整合包里那个完整版 jarjar 了 `Registrate-MC1.20-1.3.11.jar`，会和 `build.gradle` 里 implementation 的 Registrate 撞成两个同名模块，启动直接 `ResolutionException: Module Registrate contains package com.tterrag.registrate.util.entry` |
| `mixinextras-forge-0.2.0-beta.8.jar` | slim 版不带它，而本家/l2 的 mixin 用了 MixinExtras 的 `@WrapOperation`，缺了就 `ClassMetadataNotFoundException: ...wrapinjector.Operation`。这份是从整合包 l2library 里解出来的 |

换机器 / 清过缓存后，这三个 jar 要重新放一遍（`run/` 在 `.gitignore` 里，本来就不进版本库）。

## 真要跑开发环境的话

等哪天 l2library 换成一版影子字段正常写法的 jar，或者干脆在 `run/mods` 换上一份**自己重编的
l2library**，这两个开关就能一路跑到底：

```
property 'mixin.env.remapRefMap', 'true'
property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
```

## 现在怎么验证

```
./gradlew build
copy build\libs\dragom_golems-0.1.jar <整合包>\mods\
```

然后**先把 `golems_arsenal-0.3.jar` 移出 `mods`**（两边都注册了一条龙，同时装会看到两份），
进游戏看：创造栏出现"巨龙傀儡"这一页、切石机能把大傀儡胚料切成五个部件、
五个部件在铁砧上吃材料、装配台按

```
 H
LBR
 G
```

能合出巨龙傀儡，放出来会悬停、会近战、能收回。
