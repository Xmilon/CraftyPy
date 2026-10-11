package net.xmilon.craftpy.assets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds asset references in a line of Python. Pure (no Minecraft types) so it is
 * unit-testable and usable from any thread.
 *
 * <p>Recognized syntax (documented in the in-game docs):</p>
 * <ul>
 *   <li>helper calls: {@code tex("minecraft:block/dirt")}, {@code snd("minecraft:block.stone.break")},
 *       {@code mdl("minecraft:block/cube_all")}</li>
 *   <li>key form: {@code texture: minecraft:block/dirt}, {@code texture0: ...},
 *       {@code sound: ...}, {@code model: ...}, {@code icon: ...}</li>
 * </ul>
 *
 * The returned span points at the editable value (inside the quotes for the
 * helper-call form) so the picker can write the chosen id back in place.
 */
public final class AssetReferences {
    private static final Pattern CALL = Pattern.compile(
            "\\b(tex|snd|mdl)\\s*\\(\\s*(['\"])([^'\"]*)\\2\\s*\\)");
    private static final Pattern KEY = Pattern.compile(
            "\\b(texture[0-9]*|sound|model|icon)\\s*:\\s*([^\\s'\",()]+)");

    private AssetReferences() {
    }

    public static List<AssetReference> parse(String line) {
        List<AssetReference> refs = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return refs;
        }
        Matcher call = CALL.matcher(line);
        while (call.find()) {
            AssetKind kind = switch (call.group(1)) {
                case "tex" -> AssetKind.TEXTURE;
                case "snd" -> AssetKind.SOUND;
                default -> AssetKind.MODEL;
            };
            refs.add(new AssetReference(kind, call.group(3), call.start(3), call.end(3), true));
        }
        Matcher key = KEY.matcher(line);
        while (key.find()) {
            String keyName = key.group(1).toLowerCase(java.util.Locale.ROOT);
            AssetKind kind;
            if (keyName.startsWith("texture")) {
                kind = AssetKind.TEXTURE;
            } else if (keyName.equals("sound")) {
                kind = AssetKind.SOUND;
            } else if (keyName.equals("model")) {
                kind = AssetKind.MODEL;
            } else {
                kind = AssetKind.ITEM;
            }
            refs.add(new AssetReference(kind, key.group(2), key.start(2), key.end(2), false));
        }
        refs.sort(Comparator.comparingInt(AssetReference::start));
        return refs;
    }
}