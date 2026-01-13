package me.siwannie.hallowsend.modules.ritual;

import org.bukkit.Material;

import java.util.List;

public enum RitualArtifact {
    WEEPING_BELL(
            "<gradient:#00b4d8:#90e0ef><!i><bold>The Weeping Bell</bold></gradient>",
            Material.BELL,
            List.of(
                    "<gray><!i>A relic humming with ancient sorrow.",
                    "",
                    "<blue><!i>Active Ability: <white>Emit a sorrowful chime",
                    "<white><!i>that knocks back and slows nearby foes.",
                    "<dark_red><!i>Cooldown<white>: 45s"
            )
    ),
    CRYPT_LORDS_SKULL(
            "<gradient:#434343:#ffffff><!i><bold>Crypt Lord's Skull</bold></gradient>",
            Material.WITHER_SKELETON_SKULL,
            List.of(
                    "<gray><!i>The crown of a forgotten, vampiric king.",
                    "",
                    "<dark_red><!i>Passive: <white>Attacks with this skull",
                    "<white><!i>siphon health from your enemies."
            )
    ),
    CURSED_LEDGER(
            "<gradient:#c31432:#240b36><!i><bold>Silas's Cursed Ledger</bold></gradient>",
            Material.KNOWLEDGE_BOOK,
            List.of(
                    "<gray><!i>Every page whispers of dark pacts made.",
                    "",
                    "<red><!i>Active Ability: <white>Summon spectral wolves",
                    "<white><!i>to hunt your nearest target.",
                    "<dark_red><!i>Cooldown<white>: 90s"
            )
    ),
    WITCHS_HEART(
            "<gradient:#ff69b4:#c77dff><!i><bold>Warlock's Heart</bold></gradient>",
            Material.NETHER_STAR,
            List.of(
                    "<gray><!i>Still warm, it pulses with unnatural life.",
                    "",
                    "<light_purple><!i>Passive: <white>Holding this artifact",
                    "<white><!i>slowly mends your wounds."
            )
    );


    private final String displayName;
    private final Material material;
    private final List<String> lore;

    RitualArtifact(String displayName, Material material, List<String> lore) {
        this.displayName = displayName;
        this.material = material;
        this.lore = lore;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Material getMaterial() {
        return material;
    }

    public List<String> getLore() {
        return lore;
    }
}