package pl.yourserver.scientistPlugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class LoreUtil {
    public static Component abyssalLine(String tierTag, String name, String suffix) {
        Component line = Component.text("Abyssal Modifier:")
                .color(NamedTextColor.WHITE)
                .decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false);
        Component bracket = Component.text(" ")
                .decoration(TextDecoration.ITALIC, false);
        Component tier = Component.text(tierTag).decoration(TextDecoration.ITALIC, false);
        Component mod = Component.text(" ")
                .append(Component.text(name).decorate(TextDecoration.BOLD))
                .decoration(TextDecoration.ITALIC, false);
        Component extra = Component.text(" ")
                .append(Component.text(suffix).color(NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false);
        return line.append(bracket).append(tier).append(mod).append(extra);
    }
}

