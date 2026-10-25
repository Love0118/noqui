package dev.noqui.paper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Bitmap advances are measured from the shipped atlas, using Minecraft's rounding. */
final class HintGlyphs {
    static final int WIDTH = 360, ROWS = 6;
    private static final Map<String,Integer> WIDTHS = loadWidths();
    private static final String LABEL = "[힌트] ";

    private static Map<String,Integer> loadWidths() {
        try (var stream = HintGlyphs.class.getResourceAsStream("/hint-glyph-widths.json")) {
            if (stream == null) throw new IOException("Missing hint glyph metrics");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return Map.copyOf(new Gson().fromJson(reader, new TypeToken<Map<String,Integer>>() {}.getType()));
            }
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    static int width(String text) { return text.codePoints().map(c -> WIDTHS.getOrDefault(Integer.toString(c), 4)).sum(); }
    static List<String> lines(String hint) {
        var out = new ArrayList<String>();
        var line = new StringBuilder();
        int advance = 0;
        for (int code : (LABEL + hint).codePoints().toArray()) {
            int next = width(new String(Character.toChars(code)));
            if (advance + next > WIDTH && !line.isEmpty()) {
                out.add(line.toString()); line.setLength(0); advance = 0;
            }
            line.appendCodePoint(code); advance += next;
        }
        if (!line.isEmpty()) out.add(line.toString());
        if (out.size() > ROWS) throw new IllegalArgumentException("Hint exceeds the 160 character title limit");
        return List.copyOf(out);
    }

    private static Component space(int advance) { return Component.text(String.valueOf((char)(0xF000 + advance))); }
    static Component compose(String hint) {
        List<String> lines = lines(hint);
        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            int width = width(text), left = width / 2;
            Component value = i == 0
                ? Component.text(LABEL, NamedTextColor.YELLOW).append(Component.text(text.substring(LABEL.length()), NamedTextColor.WHITE))
                : Component.text(text, NamedTextColor.WHITE);
            result = result.append(Component.empty().font(Key.key("noqui_hint:row_" + (lines.size() - 1 - i)))
                .decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
                .append(space(-left)).append(value).append(space(-(width - left))));
        }
        return result;
    }
    private HintGlyphs() {}
}
