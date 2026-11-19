package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.dragon.DragonArmorItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.xkmc.modulargolems.content.client.armor.GolemModelPath;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 龙铠模型（Blockbench 导出后整理）。
 *
 * <p>几何直接来自 {@code 龙铠.bbmodel}：整条龙的骨骼 + 装甲板，512×512 图集，一件甲一套材质贴图。
 * 这里只保留网格和 {@code ModelPart} 树；姿势由 {@link DragonGolemModel#applyArmorPose(DragonArmorModel)}
 * 逐帧从本体模型拷过来（脖子/尾巴按段给相对旋转，其余部位直接抄角度）。
 *
 * <p>根级骨骼（用哪件甲就只画哪一枝）：
 * <ul>
 *   <li>龙头：{@code neck} 整枝（含 neck2..5 / head / jaw / 头部护板）</li>
 *   <li>龙身：{@code body}、{@code wing}（右翼）、{@code wing1}（左翼）</li>
 *   <li>四肢：{@code frontleg}/{@code frontleg1}（右/左前）、{@code rearleg}/{@code rearleg1}（右/左后）</li>
 *   <li>龙尾：{@code tail} 整枝（含 tail2..12 / Tail_shield1..12）</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class DragonArmorModel {
	// This layer location should be baked with EntityRendererProvider.Context in the entity renderer and passed into this model's constructor
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(new ResourceLocation("dragon_golems", "dragon_armor"), "main");

	/**
	 * 四件甲各自的甲片链（等价于本家金属傀儡的 {@code GolemModelPath}）。
	 *
	 * <p>每条链从模型根的子骨开始，最后一个是甲片；渲染时沿链把祖先的
	 * {@code translateAndRotate} 压栈，再画链尾——这样甲片才能跟着头上的骨骼走，
	 * 也才能只画甲片、不画模型里那条复制的本体龙。
	 *
	 * <p>加新甲片/新甲件时：往这里加一条链、注册一个 path，再让物品的
	 * {@code getModelPath()} 指向它即可，渲染层不用改。
	 */
	public static final java.util.List<java.util.List<String>> HEAD_PATHS = java.util.List.of(
			java.util.List.of("neck", "neck2", "neck3", "neck4", "neck5", "head", "head1"),
			java.util.List.of("neck", "head6"),
			java.util.List.of("neck", "neck2", "head5"),
			java.util.List.of("neck", "neck2", "neck3", "head4"),
			java.util.List.of("neck", "neck2", "neck3", "neck4", "head3"),
			java.util.List.of("neck", "neck2", "neck3", "neck4", "neck5", "head2"));

	public static final java.util.List<java.util.List<String>> BODY_PATHS = java.util.List.of(
			java.util.List.of("body", "body1"),
			java.util.List.of("wing", "body2"),
			java.util.List.of("wing1", "body3"));

	public static final java.util.List<java.util.List<String>> LIMB_PATHS = java.util.List.of(
			java.util.List.of("frontleg", "body4"),
			java.util.List.of("frontleg", "frontlegtip", "body5"),
			java.util.List.of("frontleg1", "body6"),
			java.util.List.of("frontleg1", "frontlegtip1", "body7"),
			java.util.List.of("rearleg", "body8"),
			java.util.List.of("rearleg", "rearlegtip", "body9"),
			java.util.List.of("rearleg1", "body10"),
			java.util.List.of("rearleg1", "rearlegtip1", "body11"));

	public static final java.util.List<java.util.List<String>> TAIL_PATHS = buildTailPaths();

	static {
		GolemModelPath.register(DragonArmorItem.PATH_HEAD, new GolemModelPath(LAYER, HEAD_PATHS));
		GolemModelPath.register(DragonArmorItem.PATH_BODY, new GolemModelPath(LAYER, BODY_PATHS));
		GolemModelPath.register(DragonArmorItem.PATH_LIMBS, new GolemModelPath(LAYER, LIMB_PATHS));
		GolemModelPath.register(DragonArmorItem.PATH_TAIL, new GolemModelPath(LAYER, TAIL_PATHS));
	}

	private static java.util.List<java.util.List<String>> buildTailPaths() {
		java.util.List<java.util.List<String>> paths = new java.util.ArrayList<>();
		paths.add(java.util.List.of("tail", "Tail_shield1"));
		java.util.List<String> chain = new java.util.ArrayList<>();
		chain.add("tail");
		for (int i = 2; i <= 12; i++) {
			chain.add("tail" + i);
			java.util.List<String> path = new java.util.ArrayList<>(chain);
			path.add("Tail_shield" + i);
			paths.add(path);
		}
		return java.util.List.copyOf(paths);
	}

	/**
	 * 甲片微调（模型像素，{x, y, z}）：在"祖先链之后、甲片自身变换之前"平移。
	 *
	 * <p>坐标系是甲片父骨的局部系：<b>+y 向下</b>、<b>+z 朝尾巴/后方</b>、+x 朝模型右侧。
	 * 想挪一片甲就改这里对应的一行——不用动导出模型，也不用改路径表。
	 * 1 格 = 16 像素；渲染时还会再乘 {@code MODEL_SCALE}（1.7）。
	 */
	private static final java.util.Map<String, float[]> PLATE_OFFSETS = buildPlateOffsets();

	private static java.util.Map<String, float[]> buildPlateOffsets() {
		java.util.Map<String, float[]> map = new java.util.HashMap<>();
		// 头甲：当前往后 6 像素（+z）
		map.put("head1", new float[]{0.0F, 0.0F, 6.0F});
		// 颈甲（五段）：脖子整体再往后一点（+z = 沿颈朝身体）；头甲 head1 已到位，不动。
		// 五段可以分别调：head6 = 最靠身体那段、head2 = 最靠头那段。
		map.put("head2", new float[]{0.0F, 0.0F, 4.0F});
		map.put("head3", new float[]{0.0F, 0.0F, 4.0F});
		map.put("head4", new float[]{0.0F, 0.0F, 4.0F});
		map.put("head5", new float[]{0.0F, 0.0F, 4.0F});
		map.put("head6", new float[]{0.0F, 0.0F, 4.0F});
		// 尾甲：当前沿尾巴往前 6 像素（-z），12 段同样处理
		for (int i = 1; i <= 12; i++) {
			map.put("Tail_shield" + i, new float[]{0.0F, 0.0F, -6.0F});
		}
		// 左右肩（双翼）甲：当前未偏移；要往下压就把 y 改成正值，抬高改成负值
		map.put("body2", new float[]{0.0F, 0.0F, 0.0F});
		map.put("body3", new float[]{0.0F, 0.0F, 0.0F});
		// 四肢甲：每片单独可调（三个数 = x, y, z；0 表示不改）。
		// 前腿：body4 = 右前大腿片、body5 = 右前小腿片；body6 = 左前大腿片、body7 = 左前小腿片。
		// 后腿：body8 = 右后大腿片、body9 = 右后小腿片；body10 = 左后大腿片、body11 = 左后小腿片。
		// 注意：小腿片的坐标系跟着小腿骨转，z 沿小腿、y 垂直于小腿；大腿片同理。
		map.put("body4", new float[]{0.0F, 0.0F, -2.0F});
		map.put("body5", new float[]{0.0F, 0.0F, 0.0F});
		map.put("body6", new float[]{0.0F, 0.0F, 0.0F});
		map.put("body7", new float[]{0.0F, 0.0F, 0.0F});
		map.put("body8", new float[]{0.0F, 0.0F, 0.0F});
		map.put("body9", new float[]{0.0F, 0.0F, 0.0F});
		map.put("body10", new float[]{0.0F, 0.0F, 0.0F});
		map.put("body11", new float[]{0.0F, 0.0F, 0.0F});
		return java.util.Map.copyOf(map);
	}

	final ModelPart neck;
	final ModelPart head6;
	final ModelPart neck2;
	final ModelPart head5;
	final ModelPart neck3;
	final ModelPart head4;
	final ModelPart neck4;
	final ModelPart head3;
	final ModelPart neck5;
	final ModelPart head2;
	final ModelPart head;
	final ModelPart head1;
	final ModelPart jaw;
	final ModelPart body;
	final ModelPart body1;
	final ModelPart wing;
	final ModelPart body2;
	final ModelPart wingtip;
	final ModelPart wing1;
	final ModelPart body3;
	final ModelPart wingtip1;
	final ModelPart rearleg;
	final ModelPart body8;
	final ModelPart rearlegtip;
	final ModelPart body9;
	final ModelPart rearfoot;
	final ModelPart rearleg1;
	final ModelPart body10;
	final ModelPart rearlegtip1;
	final ModelPart body11;
	final ModelPart rearfoot1;
	final ModelPart frontleg;
	final ModelPart body4;
	final ModelPart frontlegtip;
	final ModelPart body5;
	final ModelPart frontfoot;
	final ModelPart frontleg1;
	final ModelPart body6;
	final ModelPart frontlegtip1;
	final ModelPart body7;
	final ModelPart frontfoot1;
	final ModelPart tail;
	final ModelPart Tail_shield1;
	final ModelPart tail2;
	final ModelPart Tail_shield2;
	final ModelPart tail3;
	final ModelPart Tail_shield3;
	final ModelPart tail4;
	final ModelPart Tail_shield4;
	final ModelPart tail5;
	final ModelPart Tail_shield5;
	final ModelPart tail6;
	final ModelPart Tail_shield6;
	final ModelPart tail7;
	final ModelPart Tail_shield7;
	final ModelPart tail8;
	final ModelPart Tail_shield8;
	final ModelPart tail9;
	final ModelPart Tail_shield9;
	final ModelPart tail10;
	final ModelPart Tail_shield10;
	final ModelPart tail11;
	final ModelPart Tail_shield11;
	final ModelPart tail12;
	final ModelPart Tail_shield12;

	/** 模型根：按路径渲染甲片时从它开始往下走。 */
	final ModelPart root;

	public DragonArmorModel(ModelPart root) {
		this.root = root;
		this.neck = root.getChild("neck");
		this.head6 = this.neck.getChild("head6");
		this.neck2 = this.neck.getChild("neck2");
		this.head5 = this.neck2.getChild("head5");
		this.neck3 = this.neck2.getChild("neck3");
		this.head4 = this.neck3.getChild("head4");
		this.neck4 = this.neck3.getChild("neck4");
		this.head3 = this.neck4.getChild("head3");
		this.neck5 = this.neck4.getChild("neck5");
		this.head2 = this.neck5.getChild("head2");
		this.head = this.neck5.getChild("head");
		this.head1 = this.head.getChild("head1");
		this.jaw = this.head.getChild("jaw");
		this.body = root.getChild("body");
		this.body1 = this.body.getChild("body1");
		this.wing = root.getChild("wing");
		this.body2 = this.wing.getChild("body2");
		this.wingtip = this.wing.getChild("wingtip");
		this.wing1 = root.getChild("wing1");
		this.body3 = this.wing1.getChild("body3");
		this.wingtip1 = this.wing1.getChild("wingtip1");
		this.rearleg = root.getChild("rearleg");
		this.body8 = this.rearleg.getChild("body8");
		this.rearlegtip = this.rearleg.getChild("rearlegtip");
		this.body9 = this.rearlegtip.getChild("body9");
		this.rearfoot = this.rearlegtip.getChild("rearfoot");
		this.rearleg1 = root.getChild("rearleg1");
		this.body10 = this.rearleg1.getChild("body10");
		this.rearlegtip1 = this.rearleg1.getChild("rearlegtip1");
		this.body11 = this.rearlegtip1.getChild("body11");
		this.rearfoot1 = this.rearlegtip1.getChild("rearfoot1");
		this.frontleg = root.getChild("frontleg");
		this.body4 = this.frontleg.getChild("body4");
		this.frontlegtip = this.frontleg.getChild("frontlegtip");
		this.body5 = this.frontlegtip.getChild("body5");
		this.frontfoot = this.frontlegtip.getChild("frontfoot");
		this.frontleg1 = root.getChild("frontleg1");
		this.body6 = this.frontleg1.getChild("body6");
		this.frontlegtip1 = this.frontleg1.getChild("frontlegtip1");
		this.body7 = this.frontlegtip1.getChild("body7");
		this.frontfoot1 = this.frontlegtip1.getChild("frontfoot1");
		this.tail = root.getChild("tail");
		this.Tail_shield1 = this.tail.getChild("Tail_shield1");
		this.tail2 = this.tail.getChild("tail2");
		this.Tail_shield2 = this.tail2.getChild("Tail_shield2");
		this.tail3 = this.tail2.getChild("tail3");
		this.Tail_shield3 = this.tail3.getChild("Tail_shield3");
		this.tail4 = this.tail3.getChild("tail4");
		this.Tail_shield4 = this.tail4.getChild("Tail_shield4");
		this.tail5 = this.tail4.getChild("tail5");
		this.Tail_shield5 = this.tail5.getChild("Tail_shield5");
		this.tail6 = this.tail5.getChild("tail6");
		this.Tail_shield6 = this.tail6.getChild("Tail_shield6");
		this.tail7 = this.tail6.getChild("tail7");
		this.Tail_shield7 = this.tail7.getChild("Tail_shield7");
		this.tail8 = this.tail7.getChild("tail8");
		this.Tail_shield8 = this.tail8.getChild("Tail_shield8");
		this.tail9 = this.tail8.getChild("tail9");
		this.Tail_shield9 = this.tail9.getChild("Tail_shield9");
		this.tail10 = this.tail9.getChild("tail10");
		this.Tail_shield10 = this.tail10.getChild("Tail_shield10");
		this.tail11 = this.tail10.getChild("tail11");
		this.Tail_shield11 = this.tail11.getChild("Tail_shield11");
		this.tail12 = this.tail11.getChild("tail12");
		this.Tail_shield12 = this.tail12.getChild("Tail_shield12");

		// 链式骨骼的便捷索引：必须在构造函数里建（字段都是 final，字段初始化器里还读不到）
		this.neckChain = new ModelPart[]{neck, neck2, neck3, neck4, neck5};
		this.tailChain = new ModelPart[]{tail, tail2, tail3, tail4, tail5, tail6, tail7, tail8,
				tail9, tail10, tail11, tail12};
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition neck = partdefinition.addOrReplaceChild("neck", CubeListBuilder.create(), PartPose.offset(0.0F, 17.0F, -8.0F));

		PartDefinition head6 = neck.addOrReplaceChild("head6", CubeListBuilder.create().texOffs(196, 21).addBox(-6.0F, -13.0F, -59.0F, 12.0F, 10.0F, 11.0F, new CubeDeformation(0.0F))
		.texOffs(212, 156).addBox(-1.5F, -16.5F, -57.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 7.0F, 48.0F));

		PartDefinition neck2 = neck.addOrReplaceChild("neck2", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, -10.0F));

		PartDefinition head5 = neck2.addOrReplaceChild("head5", CubeListBuilder.create().texOffs(196, 21).addBox(-6.0F, -13.0F, -59.0F, 12.0F, 10.0F, 11.0F, new CubeDeformation(0.0F))
		.texOffs(212, 156).addBox(-1.5F, -16.5F, -57.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 7.0F, 48.0F));

		PartDefinition neck3 = neck2.addOrReplaceChild("neck3", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, -10.0F));

		PartDefinition head4 = neck3.addOrReplaceChild("head4", CubeListBuilder.create().texOffs(196, 21).addBox(-6.0F, -13.0F, -59.0F, 12.0F, 10.0F, 11.0F, new CubeDeformation(0.0F))
		.texOffs(212, 156).addBox(-1.5F, -16.5F, -57.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 7.0F, 48.0F));

		PartDefinition neck4 = neck3.addOrReplaceChild("neck4", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, -10.0F));

		PartDefinition head3 = neck4.addOrReplaceChild("head3", CubeListBuilder.create().texOffs(196, 21).addBox(-6.0F, -13.0F, -59.0F, 12.0F, 10.0F, 11.0F, new CubeDeformation(0.0F))
		.texOffs(212, 156).addBox(-1.5F, -16.5F, -57.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 7.0F, 48.0F));

		PartDefinition neck5 = neck4.addOrReplaceChild("neck5", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, -10.0F));

		PartDefinition head2 = neck5.addOrReplaceChild("head2", CubeListBuilder.create().texOffs(196, 21).addBox(-6.0F, -13.0F, -59.0F, 12.0F, 10.0F, 11.0F, new CubeDeformation(0.0F))
		.texOffs(212, 156).addBox(-1.5F, -16.5F, -57.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 7.0F, 48.0F));

		PartDefinition head = neck5.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, -10.0F));

		PartDefinition head1 = head.addOrReplaceChild("head1", CubeListBuilder.create().texOffs(0, 146).addBox(-9.0F, -16.0F, -75.0F, 18.0F, 12.0F, 18.0F, new CubeDeformation(0.0F))
		.texOffs(184, 189).addBox(-6.5F, -8.5F, -89.0F, 13.0F, 6.0F, 16.0F, new CubeDeformation(0.0F))
		.texOffs(0, 176).addBox(-8.5F, -4.5F, -74.0F, 17.0F, 6.0F, 16.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 7.0F, 58.0F));

		PartDefinition cube_r1 = head1.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(204, 84).addBox(-17.0F, -10.0F, -2.0F, 19.0F, 10.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(7.5F, -6.0F, -77.0F, -0.3491F, 0.0F, 0.0F));

		PartDefinition cube_r2 = head1.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(144, 90).mirror().addBox(-5.0F, -4.0F, -2.0F, 3.0F, 4.0F, 5.0F, new CubeDeformation(0.0F)).mirror(false)
		.texOffs(144, 90).addBox(-13.0F, -4.0F, -2.0F, 3.0F, 4.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(7.5F, -7.0F, -85.0F, -0.3491F, 0.0F, 0.0F));

		PartDefinition cube_r3 = head1.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(250, 56).mirror().addBox(-3.0F, -9.0F, -4.0F, 5.0F, 14.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false)
		.texOffs(250, 56).addBox(-12.0F, -9.0F, -4.0F, 5.0F, 13.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, -16.0F, -66.0F, -0.7854F, 0.0F, 0.0F));

		PartDefinition cube_r4 = head1.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(72, 146).mirror().addBox(0.0F, -4.0F, 0.0F, 4.0F, 6.0F, 11.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(6.0F, -6.0F, -59.0F, -0.1609F, 0.8286F, 0.0221F));

		PartDefinition cube_r5 = head1.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(212, 134).mirror().addBox(-3.774F, -3.5297F, -0.7565F, 6.0F, 6.0F, 16.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(7.0F, -13.0F, -59.0F, 0.6109F, 0.48F, 0.0F));

		PartDefinition cube_r6 = head1.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(72, 146).addBox(-3.0F, -4.0F, 0.0F, 4.0F, 6.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.0F, -6.0F, -60.0F, -0.1594F, -0.8722F, -0.0232F));

		PartDefinition cube_r7 = head1.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(212, 134).addBox(-3.0F, -3.0F, 0.0F, 6.0F, 6.0F, 16.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-6.0F, -13.0F, -60.0F, 0.6109F, -0.5236F, 0.0F));

		PartDefinition jaw = head.addOrReplaceChild("jaw", CubeListBuilder.create(), PartPose.offset(0.0F, 4.0F, -13.0F));

		PartDefinition body = partdefinition.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0.0F, 4.0F, 8.0F));

		PartDefinition body1 = body.addOrReplaceChild("body1", CubeListBuilder.create().texOffs(0, 0).addBox(-13.5F, -21.0F, -9.0F, 27.0F, 21.0F, 41.0F, new CubeDeformation(0.0F))
		.texOffs(0, 100).addBox(-7.5F, -27.0F, -4.0F, 15.0F, 6.0F, 40.0F, new CubeDeformation(0.0F))
		.texOffs(184, 172).addBox(-13.0F, -0.5F, -3.0F, 26.0F, 5.0F, 12.0F, new CubeDeformation(0.0F))
		.texOffs(110, 134).addBox(-13.0F, -20.5F, 32.0F, 26.0F, 13.0F, 25.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 20.0F, -8.0F));

		PartDefinition wing = partdefinition.addOrReplaceChild("wing", CubeListBuilder.create(), PartPose.offset(-12.0F, 5.0F, 2.0F));

		PartDefinition body2 = wing.addOrReplaceChild("body2", CubeListBuilder.create().texOffs(110, 100).addBox(-70.0F, -24.0F, -3.0F, 59.0F, 10.0F, 7.0F, new CubeDeformation(0.0F)), PartPose.offset(12.0F, 19.0F, -2.0F));

		PartDefinition wingtip = wing.addOrReplaceChild("wingtip", CubeListBuilder.create(), PartPose.offset(-56.0F, 0.0F, -2.0F));

		PartDefinition wing1 = partdefinition.addOrReplaceChild("wing1", CubeListBuilder.create(), PartPose.offset(12.0F, 5.0F, 2.0F));

		PartDefinition body3 = wing1.addOrReplaceChild("body3", CubeListBuilder.create().texOffs(110, 100).mirror().addBox(-70.0F, -24.0F, -3.0F, 59.0F, 10.0F, 7.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(69.0F, 19.0F, -2.0F));

		PartDefinition wingtip1 = wing1.addOrReplaceChild("wingtip1", CubeListBuilder.create(), PartPose.offset(56.0F, 0.0F, -2.0F));

		PartDefinition rearleg = partdefinition.addOrReplaceChild("rearleg", CubeListBuilder.create(), PartPose.offsetAndRotation(-16.0F, 16.0F, 42.0F, 1.0472F, 0.0F, 0.0F));

		PartDefinition body8 = rearleg.addOrReplaceChild("body8", CubeListBuilder.create().texOffs(144, 45).addBox(-25.0F, -13.0F, 33.0F, 12.0F, 27.0F, 18.0F, new CubeDeformation(0.0F)), PartPose.offset(16.0F, 8.0F, -42.0F));

		PartDefinition rearlegtip = rearleg.addOrReplaceChild("rearlegtip", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 28.0F, 1.0F, 0.4363F, 0.0F, 0.0F));

		PartDefinition body9 = rearlegtip.addOrReplaceChild("body9", CubeListBuilder.create().texOffs(72, 172).addBox(-23.0F, 19.0F, 35.0F, 14.0F, 30.0F, 14.0F, new CubeDeformation(0.0F)), PartPose.offset(16.0F, -20.0F, -43.0F));

		PartDefinition rearfoot = rearlegtip.addOrReplaceChild("rearfoot", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 32.0F, -2.0F, 0.7854F, 0.0F, 0.0F));

		PartDefinition rearleg1 = partdefinition.addOrReplaceChild("rearleg1", CubeListBuilder.create(), PartPose.offsetAndRotation(16.0F, 16.0F, 42.0F, 1.0472F, 0.0F, 0.0F));

		PartDefinition body10 = rearleg1.addOrReplaceChild("body10", CubeListBuilder.create().texOffs(144, 45).mirror().addBox(-25.0F, -13.0F, 33.0F, 12.0F, 27.0F, 18.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(22.0F, 8.0F, -42.0F));

		PartDefinition rearlegtip1 = rearleg1.addOrReplaceChild("rearlegtip1", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 28.0F, 1.0F, 0.4363F, 0.0F, 0.0F));

		PartDefinition body11 = rearlegtip1.addOrReplaceChild("body11", CubeListBuilder.create().texOffs(72, 172).mirror().addBox(-23.0F, 19.0F, 35.0F, 14.0F, 30.0F, 14.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(16.0F, -20.0F, -43.0F));

		PartDefinition rearfoot1 = rearlegtip1.addOrReplaceChild("rearfoot1", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 32.0F, -2.0F, 0.7854F, 0.0F, 0.0F));

		PartDefinition frontleg = partdefinition.addOrReplaceChild("frontleg", CubeListBuilder.create(), PartPose.offsetAndRotation(-12.0F, 20.0F, 2.0F, 1.1345F, 0.0F, 0.0F));

		PartDefinition body4 = frontleg.addOrReplaceChild("body4", CubeListBuilder.create().texOffs(184, 233).addBox(-18.0F, -9.0F, -1.0F, 5.0F, 17.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(12.0F, 4.0F, -2.0F));

		PartDefinition frontlegtip = frontleg.addOrReplaceChild("frontlegtip", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 20.0F, 0.0F, -0.3491F, 0.0F, 0.0F));

		PartDefinition body5 = frontlegtip.addOrReplaceChild("body5", CubeListBuilder.create().texOffs(216, 249).addBox(-16.0F, 19.0F, -3.0F, 8.0F, 15.0F, 4.0F, new CubeDeformation(0.0F))
		.texOffs(46, 198).addBox(-15.5F, 20.0F, 1.0F, 7.0F, 13.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offset(12.0F, -16.0F, -2.0F));

		PartDefinition frontfoot = frontlegtip.addOrReplaceChild("frontfoot", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 22.0F, 0.0F, 0.7854F, 0.0F, 0.0F));

		PartDefinition frontleg1 = partdefinition.addOrReplaceChild("frontleg1", CubeListBuilder.create(), PartPose.offsetAndRotation(12.0F, 20.0F, 2.0F, 1.1345F, 0.0F, 0.0F));

		PartDefinition body6 = frontleg1.addOrReplaceChild("body6", CubeListBuilder.create().texOffs(184, 233).mirror().addBox(-18.0F, -9.0F, -3.0F, 5.0F, 17.0F, 11.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(19.0F, 4.0F, -2.0F));

		PartDefinition frontlegtip1 = frontleg1.addOrReplaceChild("frontlegtip1", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 20.0F, 0.0F, -0.3491F, 0.0F, 0.0F));

		PartDefinition body7 = frontlegtip1.addOrReplaceChild("body7", CubeListBuilder.create().texOffs(216, 249).mirror().addBox(-16.0F, 19.0F, -3.0F, 8.0F, 15.0F, 4.0F, new CubeDeformation(0.0F)).mirror(false)
		.texOffs(46, 198).mirror().addBox(-15.5F, 20.0F, 1.0F, 7.0F, 13.0F, 5.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(12.0F, -16.0F, -2.0F));

		PartDefinition frontfoot1 = frontlegtip1.addOrReplaceChild("frontfoot1", CubeListBuilder.create(), PartPose.offsetAndRotation(0.0F, 22.0F, 0.0F, 0.7854F, 0.0F, 0.0F));

		PartDefinition tail = partdefinition.addOrReplaceChild("tail", CubeListBuilder.create(), PartPose.offset(0.0F, 10.0F, 56.0F));

		PartDefinition Tail_shield1 = tail.addOrReplaceChild("Tail_shield1", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r8 = Tail_shield1.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -21.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail2 = tail.addOrReplaceChild("tail2", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield2 = tail2.addOrReplaceChild("Tail_shield2", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r9 = Tail_shield2.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail3 = tail2.addOrReplaceChild("tail3", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield3 = tail3.addOrReplaceChild("Tail_shield3", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r10 = Tail_shield3.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail4 = tail3.addOrReplaceChild("tail4", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield4 = tail4.addOrReplaceChild("Tail_shield4", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r11 = Tail_shield4.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail5 = tail4.addOrReplaceChild("tail5", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield5 = tail5.addOrReplaceChild("Tail_shield5", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r12 = Tail_shield5.addOrReplaceChild("cube_r12", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail6 = tail5.addOrReplaceChild("tail6", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield6 = tail6.addOrReplaceChild("Tail_shield6", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r13 = Tail_shield6.addOrReplaceChild("cube_r13", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail7 = tail6.addOrReplaceChild("tail7", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield7 = tail7.addOrReplaceChild("Tail_shield7", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r14 = Tail_shield7.addOrReplaceChild("cube_r14", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail8 = tail7.addOrReplaceChild("tail8", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield8 = tail8.addOrReplaceChild("Tail_shield8", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r15 = Tail_shield8.addOrReplaceChild("cube_r15", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail9 = tail8.addOrReplaceChild("tail9", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield9 = tail9.addOrReplaceChild("Tail_shield9", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r16 = Tail_shield9.addOrReplaceChild("cube_r16", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail10 = tail9.addOrReplaceChild("tail10", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield10 = tail10.addOrReplaceChild("Tail_shield10", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r17 = Tail_shield10.addOrReplaceChild("cube_r17", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail11 = tail10.addOrReplaceChild("tail11", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield11 = tail11.addOrReplaceChild("Tail_shield11", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r18 = Tail_shield11.addOrReplaceChild("cube_r18", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		PartDefinition tail12 = tail11.addOrReplaceChild("tail12", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 10.0F));

		PartDefinition Tail_shield12 = tail12.addOrReplaceChild("Tail_shield12", CubeListBuilder.create().texOffs(138, 216).addBox(-6.0F, -20.0F, 55.0F, 12.0F, 8.0F, 11.0F, new CubeDeformation(0.0F))
		.texOffs(0, 62).addBox(-17.0F, -16.0F, 66.0F, 34.0F, 0.0F, 38.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 14.0F, -56.0F));

		PartDefinition cube_r19 = Tail_shield12.addOrReplaceChild("cube_r19", CubeListBuilder.create().texOffs(256, 128).addBox(-2.0F, -3.0F, -2.0F, 3.0F, 4.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5F, -20.0F, 59.0F, 0.6545F, 0.0F, 0.0F));

		return LayerDefinition.create(meshdefinition, 512, 512);
	}

	/**
	 * 按本家 {@code GolemModelPath} 的走法渲染一条路径：从模型根开始，沿名字链把<b>祖先</b>的
	 * {@code translateAndRotate} 逐个压进 pose 栈，最后画链尾那一个部件
	 * （{@code ModelPart.render} 会自己应用链尾的局部变换，所以不能重复压）。
	 *
	 * <p><b>为什么必须这样</b>：{@code ModelPart.render} 只应用它<b>自己</b>的变换；
	 * 直接调 {@code head1.render(...)} 不会带上 {@code neck → neck2 → … → head} 的旋转/位移，
	 * 甲片就会全部叠在模型原点附近（实机就是"所有甲片都堆在中心"）。
	 */
	void renderPath(PoseStack pose, VertexConsumer vc, int light, int overlay, java.util.List<String> path,
	                float alpha) {
		pose.pushPose();
		ModelPart parent = this.root;
		for (int i = 0; i < path.size() - 1; i++) {
			parent = parent.getChild(path.get(i));
			parent.translateAndRotate(pose);
		}
		// 甲片微调：在父骨坐标系里先平移，再让 render 应用甲片自身的变换。
		float[] offset = PLATE_OFFSETS.get(path.get(path.size() - 1));
		if (offset != null) {
			pose.translate(offset[0] / 16.0F, offset[1] / 16.0F, offset[2] / 16.0F);
		}
		ModelPart leaf = parent.getChild(path.get(path.size() - 1));
		leaf.render(pose, vc, light, overlay, 1.0F, 1.0F, 1.0F, alpha);
		pose.popPose();
	}

	/** 前颈/尾巴：按段摆位用（{[0] 是根，后面是子骨）。 */
	final ModelPart[] neckChain;
	final ModelPart[] tailChain;
}
