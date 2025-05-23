/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package com.moepus.createfluidstuffs.items;

import com.google.common.collect.Maps;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.mojang.math.Transformation;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.ForgeRenderTypes;
import net.minecraftforge.client.RenderTypeGroup;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.CompositeModel;
import net.minecraftforge.client.model.QuadTransformers;
import net.minecraftforge.client.model.SimpleModelState;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;
import net.minecraftforge.client.model.geometry.StandaloneGeometryBakingContext;
import net.minecraftforge.client.model.geometry.UnbakedGeometryHelper;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.function.Function;

/**
 * A dynamic fluid container model, capable of re-texturing itself at runtime to match the contained fluid.
 * <p>
 * Composed of a base layer, a fluid layer (applied with a mask) and a cover layer (optionally applied with a mask).
 * The entire model may optionally be flipped if the fluid is gaseous, and the fluid layer may glow if light-emitting.
 * <p>
 * Fluid tinting requires registering a separate {@link ItemColor}. An implementation is provided in {@link Colors}.
 *
 * @see Colors
 */
public class JarModel implements IUnbakedGeometry<JarModel>
{
    // Depth offsets to prevent Z-fighting
    private static final Transformation FLUID_TRANSFORM = new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1.002f), new Quaternionf());

    private final Fluid fluid;
    private final int color;

    private JarModel(@Nullable Fluid fluid, int color)
    {
        this.fluid = fluid == null ? Fluids.EMPTY : fluid;
        this.color = color;
    }

    public static RenderTypeGroup getLayerRenderTypes(boolean unlit)
    {
        return new RenderTypeGroup(RenderType.translucent(), unlit ? ForgeRenderTypes.ITEM_UNSORTED_UNLIT_TRANSLUCENT.get() : ForgeRenderTypes.ITEM_UNSORTED_TRANSLUCENT.get());
    }

    /**
     * Returns a new ModelDynBucket representing the given fluid, but with the same
     * other properties (flipGas, tint, coverIsMask).
     */
    public static JarModel withFluid(@Nullable Fluid newFluid, int color)
    {
        return new JarModel(newFluid, color);
    }

    private boolean isFluidEmpty()
    {
        return fluid == Fluids.EMPTY;
    }
    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides, ResourceLocation modelLocation)
    {
        Material baseLocation = context.hasMaterial("base") ? context.getMaterial("base") : null;
        Material fluidMaskLocation = context.hasMaterial("fluid") ? context.getMaterial("fluid") : null;

        TextureAtlasSprite baseSprite = baseLocation != null ? spriteGetter.apply(baseLocation) : null;
        ResourceLocation texture = IClientFluidTypeExtensions.of(fluid).getStillTexture();
        if(texture == null)
            texture = IClientFluidTypeExtensions.of(fluid).getFlowingTexture();
        Material fluidBlockMaterial = ForgeHooksClient.getBlockMaterial(texture);
        TextureAtlasSprite fluidSprite = !isFluidEmpty() ? spriteGetter.apply(fluidBlockMaterial) : null;

        TextureAtlasSprite particleSprite = fluidSprite;
        if (particleSprite == null) particleSprite = baseSprite;

        // If the fluid is lighter than air, rotate 180deg to turn it upside down
        if (!isFluidEmpty() && fluid.getFluidType().isLighterThanAir())
        {
            modelState = new SimpleModelState(
                    modelState.getRotation().compose(
                            new Transformation(null, new Quaternionf(0, 0, 1, 0), null, null)));
        }

        // We need to disable GUI 3D and block lighting for this to render properly
        var itemContext = StandaloneGeometryBakingContext.builder(context).withGui3d(false).withUseBlockLight(false).build(modelLocation);
        var modelBuilder = CompositeModel.Baked.builder(itemContext, particleSprite, new ContainedFluidOverrideHandler(overrides, baker, itemContext, this), context.getTransforms());

        var normalRenderTypes = getLayerRenderTypes(false);

        if (baseLocation != null && baseSprite != null)
        {
            // Base texture
            var unbaked = UnbakedGeometryHelper.createUnbakedItemElements(0, baseSprite.contents());
            var quads = UnbakedGeometryHelper.bakeElements(unbaked, $ -> baseSprite, modelState, modelLocation);
            modelBuilder.addQuads(normalRenderTypes, quads);
        }

        if (fluidMaskLocation != null && fluidSprite != null)
        {
            TextureAtlasSprite templateSprite = spriteGetter.apply(fluidMaskLocation);
            if (templateSprite != null)
            {
                // Fluid layer
                var transformedState = new SimpleModelState(modelState.getRotation().compose(FLUID_TRANSFORM), modelState.isUvLocked());
                var unbaked = UnbakedGeometryHelper.createUnbakedItemMaskElements(1, templateSprite.contents()); // Use template as mask
                var quads = UnbakedGeometryHelper.bakeElements(unbaked, $ -> fluidSprite, transformedState, modelLocation); // Bake with fluid texture

                if(color != 0) QuadTransformers.applyingColor(color).processInPlace(quads);

                var emissive = fluid.getFluidType().getLightLevel() > 0;
                var renderTypes = getLayerRenderTypes(emissive);
                if (emissive) {
                    QuadTransformers.settingMaxEmissivity().processInPlace(quads);
                }

                modelBuilder.addQuads(renderTypes, quads);
            }
        }

        modelBuilder.setParticle(particleSprite);

        return modelBuilder.build();
    }

    public static final class Loader implements IGeometryLoader<JarModel>
    {
        public static final Loader INSTANCE = new Loader();

        private Loader()
        {
        }

        @Override
        public JarModel read(JsonObject jsonObject, JsonDeserializationContext deserializationContext)
        {
            // create new model with correct liquid
            return withFluid(null, 0);
        }
    }

    private static final class ContainedFluidOverrideHandler extends ItemOverrides
    {
        private final Map<String, BakedModel> cache = Maps.newHashMap(); // contains all the baked models since they'll never change
        private final ItemOverrides nested;
        private final ModelBaker baker;
        private final IGeometryBakingContext owner;
        private final JarModel parent;

        private ContainedFluidOverrideHandler(ItemOverrides nested, ModelBaker baker, IGeometryBakingContext owner, JarModel parent)
        {
            this.nested = nested;
            this.baker = baker;
            this.owner = owner;
            this.parent = parent;
        }

        @Override
        public BakedModel resolve(BakedModel originalModel, ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed)
        {
            BakedModel overridden = nested.resolve(originalModel, stack, level, entity, seed);
            if (overridden != originalModel) return overridden;
            return FluidUtil.getFluidContained(stack)
                    .map(fluidStack -> {
                        Fluid fluid = fluidStack.getFluid();
                        IClientFluidTypeExtensions clientFluid = IClientFluidTypeExtensions.of(fluid);
                        int color = clientFluid.getTintColor(fluidStack);
                        String name = ForgeRegistries.FLUIDS.getKey(fluid).toString() + color;
                        if (!cache.containsKey(name))
                        {
                            JarModel unbaked = withFluid(fluid, color);
                            BakedModel bakedModel = unbaked.bake(owner, baker, Material::sprite, BlockModelRotation.X0_Y0, this, new ResourceLocation("forge:bucket_override"));
                            cache.put(name, bakedModel);
                            return bakedModel;
                        }

                        return cache.get(name);
                    })
                    // not a fluid item apparently
                    .orElse(originalModel); // empty bucket
        }
    }

    public static class Colors implements ItemColor
    {
        @Override
        public int getColor(@NotNull ItemStack stack, int tintIndex)
        {
            if (tintIndex != 1) return 0xFFFFFFFF;
            return FluidUtil.getFluidContained(stack)
                    .map(fluidStack -> IClientFluidTypeExtensions.of(fluidStack.getFluid()).getTintColor(fluidStack))
                    .orElse(0xFFFFFFFF);
        }
    }
}
