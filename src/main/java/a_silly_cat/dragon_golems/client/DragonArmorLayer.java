package a_silly_cat.dragon_golems.client;

import a_silly_cat.dragon_golems.dragon.DragonArmorItem;
import a_silly_cat.dragon_golems.dragon.DragonGolemEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.xkmc.modulargolems.content.client.armor.GolemModelPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 龙铠渲染层：<b>按本家金属傀儡那套"多护甲"写法</b>——每个物品自己声明
 * "用哪个护甲模型层、画哪几链甲片"（{@link DragonArmorItem#getModelPath()}）
 * 和"用哪张图集"（{@link DragonArmorItem#getModelTexture}），
 * 渲染层只看 {@link GolemModelPath}，不认具体是哪件甲。
 *
 * <p>流程和本家 {@code GolemEquipmentRenderer.renderArmor} 一致：
 * <ol>
 *   <li>{@code GolemModelPath.get(item.getModelPath())} 取路径表；</li>
 *   <li>按路径表里的 {@code models()} 拿/烘对应的护甲模型
 *       （{@link #models} 缓存，等价于本家的 {@code GolemEquipmentRenderer.map}，
 *       所以以后不同套甲可以用不同的模型层）；</li>
 *   <li>按物品材质取 512×512 图集；</li>
 *   <li>对每条链（{@code neck→…→head→head1}）沿祖先 {@code translateAndRotate} 后只画链尾甲片；</li>
 *   <li>本体照常画（甲片叠在上面）。</li>
 * </ol>
 *
 * <p>因此**在现有四个甲槽内**加一件新甲，只需要新物品 + 在 {@link DragonArmorModel}
 * 里注册一条 path；换一整套新模型也只是换 path 的 {@code models()}。
 * 新增槽位、或第三方 `GolemModelItem` 不是 {@link DragonArmorItem} 时，仍要改这里。
 *
 * <p>姿势由 {@link DragonGolemModel#applyArmorPose(DragonArmorModel)} 每帧从本体拷到装甲骨架，
 * 甲片挂在骨架下，所以会跟着龙一起动。
 */
@OnlyIn(Dist.CLIENT)
public class DragonArmorLayer extends RenderLayer<DragonGolemEntity, DragonGolemModel> {

    private final EntityModelSet modelSet;
    /** 模型层 → 烘好的护甲模型（可能有多套）。 */
    private final Map<ModelLayerLocation, DragonArmorModel> models = new HashMap<>();

    public DragonArmorLayer(RenderLayerParent<DragonGolemEntity, DragonGolemModel> parent,
                            EntityModelSet modelSet) {
        super(parent);
        this.modelSet = modelSet;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffer, int light, DragonGolemEntity dragon,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        if (!(hasArmor(dragon, EquipmentSlot.HEAD) || hasArmor(dragon, EquipmentSlot.CHEST)
                || hasArmor(dragon, EquipmentSlot.LEGS) || hasArmor(dragon, EquipmentSlot.FEET))) {
            return;
        }
        // 和本家 renderAllParts 的骑乘透明一致：本地玩家直接骑着这条龙时，整条龙按 0.15 透明画。
        boolean transparent = Minecraft.getInstance().player != null
                && dragon.hasPassenger(Minecraft.getInstance().player);
        pose.pushPose();
        this.getParentModel().applyRootTransform(pose);

        renderPiece(pose, buffer, light, dragon, EquipmentSlot.HEAD, transparent);
        renderPiece(pose, buffer, light, dragon, EquipmentSlot.CHEST, transparent);
        renderPiece(pose, buffer, light, dragon, EquipmentSlot.LEGS, transparent);
        renderPiece(pose, buffer, light, dragon, EquipmentSlot.FEET, transparent);

        pose.popPose();
    }

    private void renderPiece(PoseStack pose, MultiBufferSource buffer, int light, DragonGolemEntity dragon,
                             EquipmentSlot slot, boolean transparent) {
        ItemStack stack = dragon.getWrapperOfHand(slot).getItem();
        if (!(stack.getItem() instanceof DragonArmorItem item)) {
            return;
        }
        GolemModelPath path = GolemModelPath.get(item.getModelPath());
        if (path == null) {
            return;
        }
        DragonArmorModel model = this.model(path.models());
        // 骨架姿势从本体拷过来（每套模型各拷一份）。
        this.getParentModel().applyArmorPose(model);
        // 骑乘时用半透明渲染类型 + 0.15 alpha（和本家 renderPartModel 一致），
        // 否则甲片保持不透明、挡住骑手视野。
        float alpha = transparent ? 0.15F : 1.0F;
        var texture = item.getModelTexture(dragon);
        VertexConsumer vc = buffer.getBuffer(transparent
                ? RenderType.entityTranslucent(texture)
                : RenderType.entityCutoutNoCull(texture));
        for (List<String> chain : path.paths()) {
            model.renderPath(pose, vc, light, OverlayTexture.NO_OVERLAY, chain, alpha);
        }
    }

    private DragonArmorModel model(ModelLayerLocation layer) {
        return this.models.computeIfAbsent(layer, loc -> new DragonArmorModel(this.modelSet.bakeLayer(loc)));
    }

    private static boolean hasArmor(DragonGolemEntity dragon, EquipmentSlot slot) {
        return dragon.getWrapperOfHand(slot).getItem().getItem() instanceof DragonArmorItem;
    }
}
