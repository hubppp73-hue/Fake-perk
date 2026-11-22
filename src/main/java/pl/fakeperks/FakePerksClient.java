package pl.fakeperks;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tylko klient: podmienia WYŚWIETLANE bonusy perków w GUI szamana (kategoria SILA).
 * Serwer o niczym nie wie, realne wartości nie są zmieniane.
 *
 * Sloty wg pluginu: kolumny 9,11,13,15,17; perk j -> slot kolumna + 9*j
 */
public class FakePerksClient implements ClientModInitializer {

    static boolean enabled = true;

    // Kolejność jak w pluginie: kategorie (kolumny) SILA, ODPORNOSC, ZRECZNOSC, INTELIGENCJA, MAGIA,
    // w każdej 3 perki od góry do dołu. Slot = kolumna + 9 * numer perku.
    static final int[] COLS = {9, 11, 13, 15, 17};

    static final String[] NAMES = {
            "extrahit", "lifesteal", "obrazenia",             // SILA (czerwony)
            "zycie", "blokada", "odpornosc",                  // ODPORNOSC (niebieski)
            "wytrzymalosc", "spetanie", "podswietlenie",      // ZRECZNOSC (żółty)
            "fajerwerki", "szczescie", "niszczenie",          // INTELIGENCJA (zielony)
            "odbicie", "eventowki", "drugiezycie"             // MAGIA (fioletowy)
    };

    static final double[] VALUES = {
            34.73, 37.26, 31.83,
            35.94, 35.46, 29.31,
            30.83, 33.17, 31.46,
            34.08, 32.71, 30.29,
            33.94, 36.18, 32.05
    };

    static final int[] SLOTS = new int[15];
    static {
        for (int c = 0; c < COLS.length; c++)
            for (int j = 0; j < 3; j++)
                SLOTS[c * 3 + j] = COLS[c] + 9 * j;
    }

    static final Pattern NUM = Pattern.compile("\\d+(?:[.,]\\d+)?");

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GenericContainerScreen gcs) {
                // co klatkę, żeby nie mignęła prawdziwa wartość po odświeżeniu slotów z serwera
                ScreenEvents.beforeRender(screen).register((s, ctx, mx, my, delta) -> apply(gcs));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("fakeperki")
                    .then(ClientCommandManager.literal("przelacz").executes(ctx -> {
                        enabled = !enabled;
                        ctx.getSource().sendFeedback(Text.literal("FakePerki: " + (enabled ? "WŁĄCZONE" : "WYŁĄCZONE")));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("ustaw")
                            .then(ClientCommandManager.argument("perk", StringArgumentType.word())
                                    .then(ClientCommandManager.argument("wartosc", DoubleArgumentType.doubleArg(0, 1000))
                                            .executes(ctx -> {
                                                String p = StringArgumentType.getString(ctx, "perk").toLowerCase(Locale.ROOT);
                                                double v = DoubleArgumentType.getDouble(ctx, "wartosc");
                                                for (int i = 0; i < NAMES.length; i++) {
                                                    if (NAMES[i].equals(p)) {
                                                        VALUES[i] = v;
                                                        ctx.getSource().sendFeedback(Text.literal(
                                                                "FakePerki: " + p + " = " + fmt(v) + "%"));
                                                        return 1;
                                                    }
                                                }
                                                ctx.getSource().sendFeedback(Text.literal(
                                                        "Dostępne perki: " + String.join(", ", NAMES)));
                                                return 0;
                                            })))));
        });
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static void apply(GenericContainerScreen screen) {
        if (!enabled) return;
        var handler = screen.getScreenHandler();
        if (handler.slots.size() < 54) return;

        for (int i = 0; i < SLOTS.length; i++) {
            Slot slot = handler.slots.get(SLOTS[i]);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore == null) continue;

            List<Text> lines = lore.lines();
            List<Text> out = new ArrayList<>(lines.size());
            boolean changed = false;
            double oldBonus = Double.NaN;
            boolean sawBonus = false;

            for (Text line : lines) {
                String plain = line.getString();
                Text repl = line;

                if (!sawBonus && plain.contains("Twój bonus")) {
                    oldBonus = firstNumber(plain);
                    repl = replaceFirstNumber(line, VALUES[i]);
                    sawBonus = true;
                } else if (sawBonus && plain.contains("Łącznie")) {
                    double oldTotal = firstNumber(plain);
                    double clanPart = (Double.isNaN(oldTotal) || Double.isNaN(oldBonus)) ? 0 : (oldTotal - oldBonus);
                    repl = replaceFirstNumber(line, VALUES[i] + clanPart);
                }

                if (!repl.getString().equals(plain)) changed = true;
                out.add(repl);
            }

            if (changed) {
                ItemStack copy = stack.copy();
                copy.set(DataComponentTypes.LORE, new LoreComponent(out));
                slot.setStackNoCallbacks(copy);
            }
        }
    }

    private static double firstNumber(String s) {
        Matcher m = NUM.matcher(s);
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group().replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Podmienia pierwszą liczbę w linii, zachowując kolory/style każdego fragmentu. */
    private static Text replaceFirstNumber(Text line, double value) {
        List<String> strs = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        line.visit((style, str) -> {
            strs.add(str);
            styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);

        MutableText result = Text.empty();
        boolean done = false;
        for (int i = 0; i < strs.size(); i++) {
            String s = strs.get(i);
            if (!done) {
                Matcher m = NUM.matcher(s);
                if (m.find()) {
                    s = m.replaceFirst(Matcher.quoteReplacement(fmt(value)));
                    done = true;
                }
            }
            result.append(Text.literal(s).setStyle(styles.get(i)));
        }
        return result;
    }
}
