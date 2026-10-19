package net.xmilon.craftpy.block;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.xmilon.craftpy.content.MachineAccess;
import net.xmilon.craftpy.content.RotationNetworks;
import net.xmilon.craftpy.registry.KineticSlots;

public final class MachineWrenchItem extends Item {
    public MachineWrenchItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        var level = context.getLevel(); var pos = context.getClickedPos();
        if (context.getPlayer() == null || !context.getPlayer().mayBuild() || !(level.getBlockEntity(pos) instanceof MachineAccess)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        return net.xmilon.craftpy.content.MachineBackend.selected().wrench().apply(context);
    }
}
