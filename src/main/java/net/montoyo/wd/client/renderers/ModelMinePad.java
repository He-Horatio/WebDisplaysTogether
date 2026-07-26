/*
 * Copyright (C) 2018 BARBOTIN Nicolas
 */

package net.montoyo.wd.client.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

@OnlyIn(Dist.CLIENT)
public final class ModelMinePad {
	public void render(MultiBufferSource buffers, PoseStack stack) {
		// TODO: this needs completing
		// TODO: I'd like this to be able to load a model from a JSON if possible
		
		double x1 = 0.0;
		double y1 = 0.0;
		double x2 = 27.65 / 32.0 + 0.01;
		double y2 = 14.0 / 32.0 + 0.002;
		
		Matrix4f positionMatrix = stack.last().pose();
		Tesselator t = Tesselator.getInstance();
		BufferBuilder vb = t.getBuilder();
		
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
		vb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		vb.vertex(positionMatrix, (float) x1, (float) y1, 0.0f).color(0, 0, 0, 255).endVertex();
		vb.vertex(positionMatrix, (float) x2, (float) y1, 0.0f).color(0, 0, 0, 255).endVertex();
		vb.vertex(positionMatrix, (float) x2, (float) y2, 0.0f).color(0, 0, 0, 255).endVertex();
		vb.vertex(positionMatrix, (float) x1, (float) y2, 0.0f).color(0, 0, 0, 255).endVertex();
		t.end();
		
		int width = 32;
		int height = 32;
		
		float padding = 1f / 23;
		float padding1 = 1f / 21;
		
		float z = 0;
		
		VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutout(new ResourceLocation("webdisplaystogether:textures/item/model/minepad_item.png")));
		
		consumer.vertex(positionMatrix, (float) x1, (float) y1 - padding, z).color(255, 255, 255, 255).uv(1f / width, 12f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
		consumer.vertex(positionMatrix, (float) x2, (float) y1 - padding, z).color(255, 255, 255, 255).uv(19f / width, 12f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
		consumer.vertex(positionMatrix, (float) x2, (float) y2 + padding, z).color(255, 255, 255, 255).uv(19f / width, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
		consumer.vertex(positionMatrix, (float) x1, (float) y2 + padding, z).color(255, 255, 255, 255).uv(1f / width, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();

		consumer.vertex(positionMatrix, (float) x1 - padding1, (float) y1, z).color(255, 255, 255, 255).uv(0f / width, 10f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
		consumer.vertex(positionMatrix, (float) x2 + padding1, (float) y1, z).color(255, 255, 255, 255).uv(20f / width, 10f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
		consumer.vertex(positionMatrix, (float) x2 + padding1, (float) y2, z).color(255, 255, 255, 255).uv(20f / width, 1f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
		consumer.vertex(positionMatrix, (float) x1 - padding1, (float) y2, z).color(255, 255, 255, 255).uv(0f / width, 1f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
		
//		consumer.vertex(positionMatrix, (float) x2, (float) y2 + padding, z - padding).color(255, 255, 255, 255).uv(0f / width, 1f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
//		consumer.vertex(positionMatrix, (float) x2, (float) y2 + padding, z).color(255, 255, 255, 255).uv(1f / width, 1f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
//		consumer.vertex(positionMatrix, (float) x2, (float) y1 - padding, z).color(255, 255, 255, 255).uv(1f / width, 2f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
//		consumer.vertex(positionMatrix, (float) x2, (float) y1 - padding, z - padding).color(255, 255, 255, 255).uv(0f / width, 2f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
//
//		consumer.vertex(positionMatrix, (float) x1, (float) y1 - padding, z - padding).color(255, 255, 255, 255).uv(0f / width, 2f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
//		consumer.vertex(positionMatrix, (float) x1, (float) y1 - padding, z).color(255, 255, 255, 255).uv(1f / width, 2f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
//		consumer.vertex(positionMatrix, (float) x1, (float) y2 + padding, z).color(255, 255, 255, 255).uv(1f / width, 1f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
//		consumer.vertex(positionMatrix, (float) x1, (float) y2 + padding, z - padding).color(255, 255, 255, 255).uv(0f / width, 1f / height).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0.25f, 0.5f, 1).endVertex();
	}
}
