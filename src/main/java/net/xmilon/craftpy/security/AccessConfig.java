package net.xmilon.craftpy.security;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/craftpy-access.toml}: what Python code may reach outside the game. Nothing here is on for a module by
 * itself: a module first declares what it wants (module.json), the player or server owner approves it, and only then the
 * request is checked against these lists. The lists are the server owner's decision.
 */
public final class AccessConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- internet ----------------------------------------------------------------------------------------------------

    public static final ModConfigSpec.BooleanValue NET_ENABLED = BUILDER
            .comment("Master switch for web access of Python code. false = no module can reach any site.")
            .define("net.enabled", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_DOMAINS = BUILDER
            .comment("The TRUSTED list: general sites known to be fine for a module to read. It does not limit anything: a module",
                    "may declare and use any site (HTTPS, after the player approves it), but a site that is not here (or is in the",
                    "danger zone) is FLAGGED on the Modules page and in the README. An entry also covers its subdomains:",
                    "'wikipedia.org' covers en.wikipedia.org.")
            .defineListAllowEmpty("net.allowedDomains", List.of(
                    // reference and knowledge
                    "wikipedia.org", "wikimedia.org", "wiktionary.org", "wikidata.org", "minecraft.wiki", "fandom.com",
                    "stackoverflow.com", "stackexchange.com", "python.org", "pypi.org", "archive.org", "openstreetmap.org",
                    "britannica.com",
                    // Minecraft and mod sites
                    "minecraft.net", "mojang.com", "neoforged.net", "modrinth.com", "curseforge.com", "planetminecraft.com",
                    // weather, time, geography, science, space
                    "open-meteo.com", "wttr.in", "worldtimeapi.org", "timeapi.io", "restcountries.com", "nominatim.openstreetmap.org",
                    "api.nasa.gov", "nasa.gov", "api.le-systeme-solaire.net", "numbersapi.com", "sunrise-sunset.org",
                    // news and feeds
                    "bbc.com", "bbc.co.uk", "npr.org", "reuters.com", "hnrss.org", "news.ycombinator.com", "reddit.com",
                    // music, books, media metadata
                    "musicbrainz.org", "api.deezer.com", "itunes.apple.com", "openlibrary.org", "gutenberg.org",
                    "api.themoviedb.org", "omdbapi.com",
                    // money
                    "frankfurter.app", "api.coingecko.com", "api.exchangerate.host",
                    // fun and test services
                    "pokeapi.co", "dog.ceo", "catfact.ninja", "api.chucknorris.io", "official-joke-api.appspot.com",
                    "jsonplaceholder.typicode.com", "httpbin.org", "api.quotable.io", "opentdb.com", "xkcd.com",
                    // AI services (they need an API key: see net.httpKeys, the module never sees it)
                    "api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com", "api.mistral.ai",
                    "openrouter.ai", "api.groq.com"),
                    () -> "example.org", AccessConfig::validDomain);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DANGER_DOMAINS = BUILDER
            .comment("DANGER ZONE. Sites that can hold files other people uploaded, or that receive data without checking who",
                    "sends it. A module may use them only if it declares them and is approved, and the Modules page shows a red",
                    "'continue at your own risk' warning for them. Remove a line to forbid the site completely.")
            .defineListAllowEmpty("net.dangerDomains", List.of(
                    // code and file hosting: anyone can put anything there
                    "github.com", "api.github.com", "raw.githubusercontent.com", "gist.github.com", "gitlab.com",
                    "bitbucket.org", "codeberg.org", "sourceforge.net",
                    // paste and file sharing
                    "pastebin.com", "hastebin.com", "paste.ee", "ghostbin.com", "transfer.sh", "file.io", "dropbox.com",
                    "mega.nz", "drive.google.com", "docs.google.com", "mediafire.com", "wetransfer.com",
                    // chat and message endpoints: data can be sent out with one request
                    "discord.com", "discordapp.com", "api.telegram.org", "t.me", "slack.com", "hooks.slack.com",
                    "webhook.site", "requestbin.com", "pipedream.net",
                    // tunnels to someone's computer
                    "ngrok.io", "ngrok-free.app", "trycloudflare.com", "serveo.net", "localtunnel.me", "loca.lt",
                    // short links hide where they go; social networks
                    "bit.ly", "tinyurl.com", "t.co", "goo.gl", "x.com", "twitter.com", "facebook.com", "instagram.com",
                    "tiktok.com", "youtube.com", "twitch.tv", "steamcommunity.com"),
                    () -> "example.org", AccessConfig::validDomain);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> HTTP_KEYS = BUILDER
            .comment("API keys, kept here and never given to Python. Each entry: name=domain=Header: value",
                    "For example:  openai=api.openai.com=Authorization: Bearer sk-xxxx",
                    "A module writes http.get(url, key=\"openai\"); the key is added to requests to that domain only.")
            .defineListAllowEmpty("net.httpKeys", List.of(), () -> "name=example.org=Authorization: Bearer xxx",
                    o -> o instanceof String);

    public static final ModConfigSpec.IntValue MAX_REQUESTS_PER_MINUTE = BUILDER
            .comment("Requests one module may make per minute.")
            .defineInRange("net.maxRequestsPerMinute", 30, 1, 600);

    public static final ModConfigSpec.IntValue MAX_RESPONSE_KB = BUILDER
            .comment("Largest answer that is read (KB); a bigger one is refused.")
            .defineInRange("net.maxResponseKb", 2048, 1, 16384);

    public static final ModConfigSpec.IntValue MAX_REQUEST_KB = BUILDER
            .comment("Largest request body a module may send (KB).")
            .defineInRange("net.maxRequestKb", 256, 1, 4096);

    public static final ModConfigSpec.IntValue TIMEOUT_SECONDS = BUILDER
            .comment("How long one request may take, connecting and reading together.")
            .defineInRange("net.timeoutSeconds", 15, 2, 60);

    // ---- files -------------------------------------------------------------------------------------------------------

    public static final ModConfigSpec.BooleanValue FILES_ENABLED = BUILDER
            .comment("Master switch for files.* (each project's own data folder).")
            .define("files.enabled", true);

    public static final ModConfigSpec.IntValue FILES_QUOTA_MB = BUILDER
            .comment("Space one project's data folder (pyforge/data/<project>/) may use.")
            .defineInRange("files.quotaMb", 20, 1, 1024);

    public static final ModConfigSpec.IntValue FILES_MAX_FILE_KB = BUILDER
            .comment("Largest single file a module may write (KB).")
            .defineInRange("files.maxFileKb", 1024, 1, 65536);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHARED_FOLDERS = BUILDER
            .comment("Folders on this computer that modules may READ (never write), by name. Each entry: name=path",
                    "For example:  music=C:/Users/me/Music   A module reads them with files.read(\"song.txt\", folder=\"music\").",
                    "Only the files inside; no '..', no links that lead out.")
            .defineListAllowEmpty("files.sharedFolders", List.of(), () -> "name=/path/to/folder", o -> o instanceof String);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AccessConfig() {
    }

    private static boolean validDomain(Object value) {
        return value instanceof String text && NetPolicy.normalizeDomain(text) != null;
    }

    /** The value, or its default while the config is not loaded (unit tests, the standalone IDE). */
    public static <T> T get(ModConfigSpec.ConfigValue<T> value) {
        try {
            return value.get();
        } catch (IllegalStateException notLoaded) {
            return value.getDefault();
        }
    }

    public static List<String> strings(ModConfigSpec.ConfigValue<List<? extends String>> value) {
        return List.copyOf(get(value));
    }
}
