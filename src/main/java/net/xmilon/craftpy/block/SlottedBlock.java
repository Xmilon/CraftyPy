package net.xmilon.craftpy.block;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.registry.ModBlockEntities;
import net.xmilon.craftpy.registry.SlotRegistry;

import javax.annotation.Nullable;

/**
 * One generic pre-registered block slot. Behavior is driven at runtime by the
 * {@link Defs.BlockDef} currently assigned to this index; unassigned slots
 * behave as an inert placeholder so placed blocks never corrupt a save.
 */
public class SlottedBlock extends Block implements EntityBlock {
    public static final IntegerProperty CRAFTPY_LIGHT = IntegerProperty.create("craftpy_light", 0, 15);

    private final int slotIndex;

    public SlottedBlock(int slotIndex, Properties properties) {
        super(properties.mapColor(MapColor.STONE).strength(2.0F, 6.0F));
        this.slotIndex = slotIndex;
        this.registerDefaultState(this.stateDefinition.any().setValue(CRAFTPY_LIGHT, 0));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SlottedBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.SLOTTED_BLOCK_ENTITY.get(),
                (lvl, pos, st, be) -> be.tick(lvl, pos, st));
    }

    @SuppressWarnings("unchecked")
    protected static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actualType, BlockEntityType<E> expectedType, BlockEntityTicker<? super E> ticker) {
        return expectedType == actualType ? (BlockEntityTicker<A>) ticker : null;
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SlottedBlockEntity sbe) {
                sbe.interact(player, pos, state);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        Defs.BlockDef def = definition();
        return def != null ? def.light : 0;
    }

    /**
     * Slot blocks are in no vanilla "mineable" tag, so the tool comes from the definition:
     * the matching tool mines at its tier speed; with needs_tool the wrong tool mines slowly
     * (and the generated loot table drops nothing, like vanilla ores).
     */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        Defs.BlockDef def = definition();
        float hardness = def != null && def.hardness > 0 ? def.hardness : 2.0f;
        String tool = def == null ? "" : def.tool;
        ItemStack held = player.getMainHandItem();
        boolean correct = isCorrectTool(tool, held, def == null ? 0 : def.minLevel);
        // getDestroySpeed already applies haste / mining fatigue / underwater to the base speed 1.
        float speed = player.getDestroySpeed(state);
        if (correct && !tool.isBlank()) {
            speed *= Math.max(1.0f, held.getDestroySpeed(proxyFor(tool)));
        }
        boolean dropsOk = def == null || !def.needsTool || correct;
        return speed / hardness / (dropsOk ? 30 : 100);
    }

    /** True when no tool is required, or the held item is in the matching vanilla tool tag. */
    public static boolean isCorrectTool(String tool, ItemStack held) {
        return isCorrectTool(tool, held, 0);
    }

    /**
     * The held item is a tool of this kind (a vanilla tool, or an item with a Tool extension) of at least this mining
     * level (0 wood/gold, 1 stone, 2 iron, 3 diamond, 4 netherite). The level only matters for pickaxe-style blocks
     * when a vanilla tool is held; custom tools carry their own level for every kind.
     */
    public static boolean isCorrectTool(String tool, ItemStack held, int minLevel) {
        if (tool == null || tool.isBlank()) {
            return true;
        }
        String kind = tool.toLowerCase(Locale.ROOT);
        if (held.getItem() instanceof SlottedItem custom) {
            return custom.toolKind().equals(kind) && custom.toolLevel() >= minLevel;
        }
        var tag = toolTag(tool);
        if (tag == null || !held.is(tag)) {
            return false;
        }
        if (minLevel <= 0 || !kind.equals("pickaxe")) {
            return true;
        }
        var proxy = minLevel == 1 ? net.minecraft.world.level.block.Blocks.IRON_ORE
                : minLevel == 2 ? net.minecraft.world.level.block.Blocks.DIAMOND_ORE
                : net.minecraft.world.level.block.Blocks.OBSIDIAN;
        return held.isCorrectToolForDrops(proxy.defaultBlockState());
    }

    @Nullable
    public static net.minecraft.tags.TagKey<Item> toolTag(String tool) {
        return switch (tool.toLowerCase(Locale.ROOT)) {
            case "pickaxe" -> net.minecraft.tags.ItemTags.PICKAXES;
            case "axe" -> net.minecraft.tags.ItemTags.AXES;
            case "shovel" -> net.minecraft.tags.ItemTags.SHOVELS;
            case "hoe" -> net.minecraft.tags.ItemTags.HOES;
            case "sword" -> net.minecraft.tags.ItemTags.SWORDS;
            default -> null;
        };
    }

    /** A vanilla block each tool is made for, used to read the tool's tier speed. */
    private static BlockState proxyFor(String tool) {
        return switch (tool.toLowerCase(Locale.ROOT)) {
            case "axe" -> net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState();
            case "shovel" -> net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
            case "hoe" -> net.minecraft.world.level.block.Blocks.HAY_BLOCK.defaultBlockState();
            case "sword" -> net.minecraft.world.level.block.Blocks.COBWEB.defaultBlockState();
            default -> net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        };
    }


    // ------------------------------------------------------------------
    // Extensions (Defs.BlockDef): redstone, fire, friction, pistons
    // ------------------------------------------------------------------

    @Override
    protected boolean isSignalSource(BlockState state) {
        Defs.BlockDef def = definition();
        return def != null && def.redstone > 0;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        Defs.BlockDef def = definition();
        return def == null ? 0 : def.redstone;
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        Defs.BlockDef def = definition();
        return def != null && def.burnChance >= 0 ? def.burnChance : super.getFlammability(state, level, pos, face);
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        Defs.BlockDef def = definition();
        return def != null && def.burnChance >= 0 ? def.burnSpread : super.getFireSpreadSpeed(state, level, pos, face);
    }

    @Override
    public float getFriction(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        Defs.BlockDef def = definition();
        return def != null && def.friction >= 0 ? def.friction : super.getFriction(state, level, pos, entity);
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        Defs.BlockDef def = definition();
        if (def == null) {
            return super.getPistonPushReaction(state);
        }
        return switch (def.push) {
            case "destroy" -> PushReaction.DESTROY;
            case "block" -> PushReaction.BLOCK;
            case "ignore" -> PushReaction.IGNORE;
            case "push_only" -> PushReaction.PUSH_ONLY;
            case "normal" -> PushReaction.NORMAL;
            default -> super.getPistonPushReaction(state);
        };
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