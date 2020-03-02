package com.direwolf20.buildinggadgets.common.building.modes;

import com.direwolf20.buildinggadgets.common.building.placement.IPositionPlacementSequence;
import com.direwolf20.buildinggadgets.common.building.placement.PlacementSequences.Column;
import com.direwolf20.buildinggadgets.common.building.view.IValidatorFactory;
import com.direwolf20.buildinggadgets.common.config.Config;
import com.direwolf20.buildinggadgets.common.util.lang.ModeTranslation;
import com.direwolf20.buildinggadgets.common.util.ref.Reference;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Direction;
import net.minecraft.util.Direction.Axis;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nonnull;

/**
 * Logic is backed with {@link Column#createAxisChasing(BlockPos, BlockPos, Axis, int)}} where no attempt will be made at the ending (player) position.
 * <p>
 * This mode is designed for Building Gadget and does not guarantee to work with other gadgets.
 */
public class BuildToMeMode extends AtopSupportedMode {

    private static final ResourceLocation NAME = new ResourceLocation(Reference.MODID, "axis_chasing");

    public BuildToMeMode(IValidatorFactory validatorFactory) {
        super(validatorFactory);
    }

    @Override
    public IPositionPlacementSequence computeWithTransformed(PlayerEntity player, BlockPos transformed, BlockPos original, Direction sideHit, ItemStack tool) {
        int range = Config.GADGETS.maxRange.get();
        return Column.createAxisChasing(transformed, new BlockPos(Math.floor(player.getPosX()), Math.floor(player.getPosY()), Math.floor(player.getPosY())), sideHit, range);
    }

    @Override
    public BlockPos transformAtop(PlayerEntity player, BlockPos hit, Direction sideHit, ItemStack tool) {
        return hit.offset(sideHit);
    }

    @Override
    public ResourceLocation getRegistryName() {
        return NAME;
    }

    @Override
    @Nonnull
    public String getLocalizedName() {
        return ModeTranslation.AXIS_CHASING.format();
    }

}
