package net.xmilon.craftpy.assets;

/**
 * One recognized asset reference inside a single line of source code.
 *
 * @param kind  what the reference points at
 * @param value the resource id text (without surrounding quotes for call forms)
 * @param start inclusive index of the first character of the editable value
 * @param end   exclusive index just past the editable value
 * @param call  true when produced by {@code tex(..)} / {@code snd(..)} / {@code mdl(..)}
 */
public record AssetReference(AssetKind kind, String value, int start, int end, boolean call) {
}