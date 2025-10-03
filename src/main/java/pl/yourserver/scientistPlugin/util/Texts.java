package pl.yourserver.scientistPlugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Locale;

public class Texts {
    public static Component legacy(String s) {
        try {
            Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
            return c.decoration(TextDecoration.ITALIC, false);
        } catch (Throwable t) {
            return Component.text(s == null ? "" : s).decoration(TextDecoration.ITALIC, false);
        }
    }

    public static Component tierTag(int tier) {
        String txt = switch (tier) { case 1 -> "[ I ]"; case 2 -> "[ II ]"; default -> "[ III ]"; };
        NamedTextColor color = switch (tier) { case 1 -> NamedTextColor.BLUE; case 2 -> NamedTextColor.DARK_PURPLE; default -> NamedTextColor.GOLD; };
        return Component.text(txt).color(color).decoration(TextDecoration.ITALIC, false);
    }

    public static String prettyKey(String key) {
        if (key == null || key.isEmpty()) return "";
        String[] parts = key.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.length() > 1 ? p.substring(1) : "").append(" ");
        }
        return sb.toString().trim();
    }
}

