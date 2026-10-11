package net.xmilon.craftpy.block;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * One generic pre-registered block slot. Behavior is driven at runtime by the
 * {@link Defs.BlockDef} currently assigned to this index; unassigned slots
 * behave as an inert placeholder so placed blocks never corrupt a save.
 */
public class SlottedBlock extends Block {
    public static final IntegerProperty CRAFTPY_LIGHT = IntegerProperty.create("craftpy_light", 0, 15);

    private final int slotIndex;

    public SlottedBlock(int slotIndex, Properties properties) {
        super(properties.mapColor(MapColor.STONE).strength(2.0F, 6.0F));
        this.slotIndex = slotIndex;
        this.registerDefaultState(this.stateDefinition.any().setValue(CRAFTPY_LIGHT, 0));
    }

    public int slotIndex() {
        return slotIndex;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CRAFTPY_LIGHT);
        super.createBlockStateDefinition(builder);
    }

    public Defs.BlockDef definition() {
        return SlotRegistry.blockDef(slotIndex);
    }

    public boolean isUnloadedPlaceholder() {
        return definition() == null;
    }

    @Override
    public MutableComponent getName() {
        Defs.BlockDef def = definition();
        if (def != null && def.name != null) {
            return Component.literal(def.name);
        }
        return Component.literal("Unloaded: " + SlotRegistry.holderForBlock(slotIndex));
    }

    @Override
    public String getDescriptionId() {
        Defs.BlockDef def = definition();
        if (def != null && def.name != null && !def.name.isBlank()) {
            return def.name;
        }
        return "block.craftpy.unloaded";
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines,
            TooltipFlag flags) {
        Defs.BlockDef def = definition();
        String holder = SlotRegistry.holderForBlock(slotIndex);
        lines.add(Component.literal(holder).withStyle(ChatFormatting.DARK_GRAY));
        if (def == null) {
            lines.add(Component.literal("Unloaded - load the project to activate.").withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, context, lines, flags);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        Defs.BlockDef def = definition();
        return def != null ? def.light : 0;
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        Defs.BlockDef def = definition();
        float speed = def != null && def.hardness > 0 ? def.hardness : 2.0f;
        if (def != null && def.needsTool && !player.hasCorrectToolForDrops(state)) {
            return 0.0f;
        }
        if (speed == -1.0F) {
            return 0.0F;
        }
        int divisor = player.hasCorrectToolForDrops(state) ? 30 : 100;
        float playerSpeed = player.getDestroySpeed(state);
        return playerSpeed / speed / divisor;
    }

    @Override
    public float getExplosionResistance() {
        Defs.BlockDef def = definition();
        return def != null && def.resistance > 0 ? def.resistance : 6.0f;
    }

    @Override
    public SoundType getSoundType(BlockState state) {
        Defs.BlockDef def = definition();
        if (def != null && !def.sound.isBlank()) {
            return SoundTypes.fromName(def.sound);
        }
        return SoundType.STONE;
    }

    /** Maps a handful of common sound names to vanilla SoundTypes. */
    public static final class SoundTypes {
        private SoundTypes() {
        }

        public static SoundType fromName(String raw) {
            String name = raw.toLowerCase(Locale.ROOT);
            if (name.contains("wood")) {
                return SoundType.WOOD;
            }
            if (name.contains("grass")) {
                return SoundType.GRASS;
            }
            if (name.contains("glass")) {
                return SoundType.GLASS;
            }
            if (name.contains("gravel")) {
                return SoundType.GRAVEL;
            }
            if (name.contains("sand")) {
                return SoundType.SAND;
            }
            if (name.contains("wool")) {
                return SoundType.WOOL;
            }
            if (name.contains("metal")) {
                return SoundType.METAL;
            }
            if (name.contains("anvil")) {
                return SoundType.ANVIL;
            }
            if (name.contains("snow")) {
                return SoundType.SNOW;
            }
            if (name.contains("stone")) {
                return SoundType.STONE;
            }
            return SoundType.STONE;
        }
    }
}