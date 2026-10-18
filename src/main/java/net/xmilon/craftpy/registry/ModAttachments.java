package net.xmilon.craftpy.registry;

import java.util.function.Supplier;

import com.mojang.serialization.Codec;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.xmilon.craftpy.CraftPyLog;

/** Data CraftPy keeps on players. */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, CraftPyLog.MODID);

    /** The player crafted a CraftPy Computer: the quick IDE key works for them. Kept through death. */
    public static final Supplier<AttachmentType<Boolean>> IDE_UNLOCKED = TYPES.register("ide_unlocked",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath().build());

    private ModAttachments() {
    }
}
