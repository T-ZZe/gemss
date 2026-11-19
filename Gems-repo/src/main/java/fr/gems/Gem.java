package fr.gems;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

public enum Gem {
    WHITE("White Gem", NamedTextColor.WHITE, Material.WHITE_DYE,
            "Passif : invisibilité permanente",
            "Clic gauche : semi-intangible 15s (cooldown 60s)"),

    RED("Red Gem", NamedTextColor.RED, Material.RED_DYE,
            "Passif : critiques automatiques dans le Nether",
            "Clic droit : tracking",
            "Sneak + clic droit : force x1.25"),

    GREEN("Green Gem", NamedTextColor.GREEN, Material.GREEN_DYE,
            "Passif : Héros du village V permanent",
            "Clic gauche sur un joueur : Hunger III"),

    PINK("Pink Gem", NamedTextColor.LIGHT_PURPLE, Material.PINK_DYE,
            "Passif : +2 cœurs permanents",
            "Clic droit : répare ton armure"),

    YELLOW("Yellow Gem", NamedTextColor.YELLOW, Material.YELLOW_DYE,
            "Passif : saturation complète avec n'importe quelle nourriture (cooldown)",
            "Clic droit : épée -> mace pendant 30s (non enchantée)"),

    BLUE("Blue Gem", NamedTextColor.BLUE, Material.BLUE_DYE,
            "Passif : Speed I permanent",
            "Clic droit : dash ~10 blocs (cooldown 30s)",
            "Interdit en sprintant"),

    BLACK("Black Gem", NamedTextColor.DARK_GRAY, Material.BLACK_DYE,
            "Passif : 15 cœurs dans l'End",
            "Passif : immunité à l'effet Wither",
            "Clic droit : lance 3 têtes de Wither (cooldown 2min)"),

    PURPLE("Purple Gem", NamedTextColor.DARK_PURPLE, Material.PURPLE_DYE,
            "Sneak + clic droit : téléportation là où tu regardes",
            "Clic droit : freecam 15s (cooldown 5min)"),

    ORANGE("Orange Gem", NamedTextColor.GOLD, Material.ORANGE_DYE,
            "Passif : résistance au feu"),

    BLOCK("Gem Block", NamedTextColor.AQUA, Material.NETHER_STAR,
            "Passif : tous les effets positifs (sauf saturation)",
            "Passif : un éclair à chaque coup",
            "Passif : 20 cœurs",
            "Clic droit : dash",
            "Sneak + clic droit : auto-crit 15s (cooldown 1min)");

    public final String displayName;
    public final TextColor color;
    public final Material material;
    public final String[] lore;

    Gem(String displayName, TextColor color, Material material, String... lore) {
        this.displayName = displayName;
        this.color = color;
        this.material = material;
        this.lore = lore;
    }
}
