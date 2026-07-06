package pl.yourserver.scientistPlugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoreUtil {
    public static Component abyssalLine(String tierTag, String name, String suffix) {
        Component prefix = Component.text("ABYSSAL")
                .color(NamedTextColor.DARK_BLUE)
                .decoration(TextDecoration.ITALIC, false);
        Component tier = Texts.legacy(tierTag);
        Component nameComponent = Component.text(name + " - ")
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
        Component suffixComponent = highlightValues(suffix);
        return prefix
                .append(Component.text(" ").decoration(TextDecoration.ITALIC, false))
                .append(tier.decoration(TextDecoration.ITALIC, false))
                .append(Component.text(" ").decoration(TextDecoration.ITALIC, false))
                .append(nameComponent)
                .append(suffixComponent);
    }

    private static Component highlightValues(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return Component.text("")
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false);
        }
        Pattern pattern = Pattern.compile("-?\\d+(?:\\.\\d+)?%?");
        Matcher matcher = pattern.matcher(suffix);
        int last = 0;
        Component result = Component.empty();
        while (matcher.find()) {
            if (matcher.start() > last) {
                result = appendSegment(result, suffix.substring(last, matcher.start()), false);
            }
            result = appendSegment(result, matcher.group(), true);
            last = matcher.end();
        }
        if (last < suffix.length()) {
            result = appendSegment(result, suffix.substring(last), false);
        }
        return result;
    }

    private static Component appendSegment(Component base, String text, boolean value) {
        NamedTextColor color = value ? NamedTextColor.GOLD : NamedTextColor.GRAY;
        return base.append(Component.text(text)
                .color(color)
                .decoration(TextDecoration.ITALIC, false));
    }
}
