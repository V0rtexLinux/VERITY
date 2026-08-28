package com.mod.echo.assistant;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ECHO's offline Minecraft reference.
 *
 * Everything here is plain data, so it answers instantly and still works when no
 * language model is running at all.  Lookup keys accept both English and
 * Portuguese so the same entry serves either language.
 */
public final class KnowledgeBase {

    private KnowledgeBase() {}

    // ------------------------------------------------------------------ //
    //  Crafting                                                            //
    // ------------------------------------------------------------------ //

    private static final Map<String, String> CRAFTING = new LinkedHashMap<>();
    static {
        CRAFTING.put("pickaxe|picareta",
                "Pickaxe: 3 of the material across the top row, 2 sticks down the middle column.");
        CRAFTING.put("sword|espada",
                "Sword: 2 of the material stacked vertically, 1 stick underneath.");
        CRAFTING.put("axe|machado",
                "Axe: 2 material in the top row plus 1 below the left one, then 2 sticks under that.");
        CRAFTING.put("shovel|pa|pá",
                "Shovel: 1 material on top, 2 sticks below it.");
        CRAFTING.put("hoe|enxada",
                "Hoe: 2 material across the top-left, 2 sticks down the middle column.");
        CRAFTING.put("enchanting table|enchanting|mesa de encantamento|encantamento",
                "Enchanting table: 1 book on top, 2 diamonds beside it, 4 obsidian filling the bottom.");
        CRAFTING.put("anvil|bigorna",
                "Anvil: 3 iron blocks across the top, 1 iron ingot in the centre, 3 iron ingots along the bottom. (31 ingots total.)");
        CRAFTING.put("beacon|farol",
                "Beacon: 3 glass on top, Nether star in the centre, 3 obsidian along the bottom. Needs a 3x3 mineral pyramid under it.");
        CRAFTING.put("shield|escudo",
                "Shield: 6 planks in a U with 1 iron ingot in the top-middle slot.");
        CRAFTING.put("bow|arco",
                "Bow: 3 string down the right column, 3 sticks in a diagonal arc.");
        CRAFTING.put("crossbow|besta",
                "Crossbow: 3 sticks + 2 string + 1 iron ingot + 1 tripwire hook.");
        CRAFTING.put("brewing stand|suporte de pocoes|poções",
                "Brewing stand: 1 blaze rod on top, 3 cobblestone across the middle row.");
        CRAFTING.put("cauldron|caldeirao",
                "Cauldron: 7 iron ingots in a U shape.");
        CRAFTING.put("ender chest|bau do end|baú do end",
                "Ender chest: 8 obsidian around 1 Eye of Ender.");
        CRAFTING.put("hopper|funil",
                "Hopper: 5 iron ingots in a V with a chest in the centre.");
        CRAFTING.put("golden apple|maca dourada|maçã dourada",
                "Golden apple: 8 gold ingots around 1 apple.");
        CRAFTING.put("book|livro",
                "Book: 3 paper + 1 leather anywhere in the grid.");
        CRAFTING.put("bookshelf|estante",
                "Bookshelf: 6 planks (top and bottom rows) + 3 books in the middle row. 15 around a table gives level 30.");
        CRAFTING.put("boat|barco",
                "Boat: 5 planks in a U shape.");
        CRAFTING.put("minecart|carrinho",
                "Minecart: 5 iron ingots in a U shape.");
        CRAFTING.put("rail|trilho",
                "Rails: 6 iron ingots down the outer columns + 1 stick in the centre. Makes 16.");
        CRAFTING.put("elytra|elitra",
                "Elytra cannot be crafted — it is found in the item frame of an End City ship.");
        CRAFTING.put("totem|totem of undying",
                "The totem of undying cannot be crafted — evokers drop it in woodland mansions and raids.");
        CRAFTING.put("smithing|netherite",
                "Netherite gear: diamond gear + 1 netherite ingot + 1 netherite upgrade template in a smithing table. "
              + "Netherite ingot = 4 netherite scrap + 4 gold ingots.");
        CRAFTING.put("lodestone|pedra-guia",
                "Lodestone: 8 chiselled stone bricks around 1 netherite ingot. A compass clicked on it points there forever.");
    }

    // ------------------------------------------------------------------ //
    //  Enchanting                                                          //
    // ------------------------------------------------------------------ //

    private static final Map<String, String> ENCHANTS = new LinkedHashMap<>();
    static {
        ENCHANTS.put("sword|espada",
                "Sword: Sharpness V, Looting III, Sweeping Edge III, Unbreaking III, Mending. Fire Aspect II if you want cooked drops.");
        ENCHANTS.put("pickaxe|picareta",
                "Pickaxe: Efficiency V, Unbreaking III, Mending, and then either Fortune III (ores) or Silk Touch (blocks). Keep one of each.");
        ENCHANTS.put("axe|machado",
                "Axe: Efficiency V, Unbreaking III, Mending. Sharpness V turns it into the highest-damage weapon in the game.");
        ENCHANTS.put("shovel|pa|pá",
                "Shovel: Efficiency V, Unbreaking III, Mending, Silk Touch for gravel into gravel.");
        ENCHANTS.put("bow|arco",
                "Bow: Power V, Infinity, Flame, Unbreaking III. Infinity and Mending are mutually exclusive.");
        ENCHANTS.put("crossbow|besta",
                "Crossbow: Quick Charge III, Multishot or Piercing IV, Unbreaking III, Mending.");
        ENCHANTS.put("trident|tridente",
                "Trident: Loyalty III + Channeling for a thrown build, or Riptide III for rain travel. Impaling V underwater.");
        ENCHANTS.put("helmet|capacete",
                "Helmet: Protection IV, Respiration III, Aqua Affinity, Unbreaking III, Mending.");
        ENCHANTS.put("chestplate|peitoral",
                "Chestplate: Protection IV, Unbreaking III, Mending. Thorns III if you can spare the durability.");
        ENCHANTS.put("leggings|calcas|calças",
                "Leggings: Protection IV, Unbreaking III, Mending. Swift Sneak III from ancient cities is worth hunting for.");
        ENCHANTS.put("boots|botas",
                "Boots: Protection IV, Feather Falling IV, Depth Strider III (or Frost Walker II), Unbreaking III, Mending.");
        ENCHANTS.put("armor|armadura",
                "Full set: Protection IV everywhere, Unbreaking III, Mending, Feather Falling IV on the boots.");
        ENCHANTS.put("fishing|cana|rod",
                "Fishing rod: Luck of the Sea III, Lure III, Unbreaking III, Mending.");
        ENCHANTS.put("mace|maca",
                "Mace: Density V with Wind Burst III — damage scales with how far you fall onto the target.");
    }

    // ------------------------------------------------------------------ //
    //  Potions                                                             //
    // ------------------------------------------------------------------ //

    private static final Map<String, String> POTIONS = new LinkedHashMap<>();
    static {
        POTIONS.put("strength|forca|força",
                "Strength: Awkward + blaze powder. Glowstone for level II, redstone to extend.");
        POTIONS.put("healing|cura",
                "Healing: Awkward + glistering melon slice. Glowstone for level II; duration cannot be extended.");
        POTIONS.put("regeneration|regen|regeneracao",
                "Regeneration: Awkward + ghast tear.");
        POTIONS.put("swiftness|speed|velocidade",
                "Swiftness: Awkward + sugar.");
        POTIONS.put("fire resistance|fire|fogo",
                "Fire resistance: Awkward + magma cream. Essential before the Nether.");
        POTIONS.put("water breathing|agua|água",
                "Water breathing: Awkward + pufferfish.");
        POTIONS.put("night vision|visao|visão",
                "Night vision: Awkward + golden carrot. Add a fermented spider eye to get invisibility.");
        POTIONS.put("invisibility|invisibilidade",
                "Invisibility: brew night vision first, then add a fermented spider eye.");
        POTIONS.put("slow falling|queda",
                "Slow falling: Awkward + phantom membrane.");
        POTIONS.put("leaping|jump|salto",
                "Leaping: Awkward + rabbit's foot.");
        POTIONS.put("poison|veneno",
                "Poison: Awkward + spider eye. Add a fermented spider eye to turn it into harming.");
        POTIONS.put("harming|dano",
                "Harming: brew healing or poison, then add a fermented spider eye. Throw it as a splash potion.");
        POTIONS.put("turtle master|tartaruga",
                "Turtle master: Awkward + turtle scute. Slowness IV plus Resistance III — great for a boss fight you can stand still in.");
    }

    // ------------------------------------------------------------------ //
    //  Combat                                                              //
    // ------------------------------------------------------------------ //

    private static final Map<String, String> MOBS = new LinkedHashMap<>();
    static {
        MOBS.put("creeper",
                "Creeper: 1.5s fuse. Hit it and step back, or let a cat/ocelot scare it off. Blast Protection stops most of the damage.");
        MOBS.put("skeleton|esqueleto",
                "Skeleton: close the distance in a zig-zag, or block arrows with a shield. Their bows are useless in melee range.");
        MOBS.put("enderman|endermen",
                "Enderman: do not look at the head. A boat, a 2-block-high tunnel or water all shut them down.");
        MOBS.put("witch|bruxa",
                "Witch: it heals itself, so burst it down. Attack while it is drinking — it cannot throw and drink at once.");
        MOBS.put("wither skeleton|wither esqueleto",
                "Wither skeleton: gives the wither effect, so drink milk after. Smite V doubles your damage.");
        MOBS.put("blaze",
                "Blaze: fire resistance potion plus snowballs (3 damage each). Fight it from inside a corridor.");
        MOBS.put("ghast",
                "Ghast: punch its fireball back at it, or shoot it with a bow. One hit kills it.");
        MOBS.put("warden",
                "Warden: do not fight it. Wool muffles your steps, and a snowball thrown elsewhere pulls its attention away.");
        MOBS.put("wither",
                "Wither: build it in a bedrock pocket in the Nether roof or under the End's bedrock. Smite V, and switch to a bow for the second phase.");
        MOBS.put("dragon|dragao|dragão",
                "Ender dragon: destroy every End crystal first (bow for the caged ones), then hit it while it perches. Bring a bed for perch damage.");
        MOBS.put("piglin",
                "Piglin: wear one piece of gold armour and they ignore you. Never open a chest in front of them.");
        MOBS.put("guardian|guardiao|guardião",
                "Guardian: its laser needs line of sight, so break it with a pillar. Do not melee an elder guardian's spikes.");
        MOBS.put("phantom|fantasma",
                "Phantom: sleep every three days, or hold a cat nearby. They burn in daylight.");
        MOBS.put("pillager|saqueador|raid|assalto",
                "Raid: a bow with Power and a crossbow for the ravagers. Drink milk to clear Bad Omen before entering a village.");
    }

    // ------------------------------------------------------------------ //
    //  General guides                                                      //
    // ------------------------------------------------------------------ //

    private static final Map<String, String> GUIDES = new LinkedHashMap<>();
    static {
        GUIDES.put("y level|best y|melhor y|onde minar|mining",
                """
                Best mining levels:
                  diamond  Y -59   (branch-mine Y -64..-50)
                  redstone Y -59
                  lapis    Y 0
                  gold     Y -16   (Y 32 in badlands)
                  iron     Y 16 and Y 232
                  copper   Y 48
                  emerald  Y 236, mountains only
                  coal     Y 96
                  ancient debris Y 15 in the Nether""");
        GUIDES.put("nether portal|portal|coordinates|coordenadas",
                "Nether coordinates are Overworld / 8. To link a portal reliably, build both ends by hand at the matched coordinates.");
        GUIDES.put("xp|experience|experiencia|experiência",
                "Fastest XP: a blaze spawner farm in a Nether fortress, then enderman farming in the End. "
              + "Ore smelting and bottles o' enchanting are good early on.");
        GUIDES.put("food|comida",
                "Cooked steak and porkchop are the best all-round food. Golden carrots have the best saturation. "
              + "Never rely on rotten flesh or raw chicken.");
        GUIDES.put("sleep|dormir|phantom",
                "Sleeping resets the phantom timer and skips the night. On a server, everyone (or the percentage set by "
              + "playersSleepingPercentage) has to sleep.");
        GUIDES.put("village|aldeia|trading|trade|comercio|comércio",
                "Villager trading: librarians are the cheapest Mending source. Break and replace a lectern to reroll an "
              + "unemployed villager. Curing a zombie villager permanently drops its prices.");
        GUIDES.put("farm|farming|fazenda|plantar",
                """
                Crop farming basics:
                  - Farmland needs water within 4 blocks in every direction.
                  - A 9x9 plot with one central water block is the classic layout.
                  - Light it to level 9+ so crops keep growing at night.
                  - Bone meal instantly grows wheat, carrots, potatoes and beetroot.""");
        GUIDES.put("redstone",
                """
                Redstone essentials:
                  - Signal strength is 15 and drops by 1 per block; a repeater restores it.
                  - A comparator reads container fullness and does subtraction mode.
                  - An observer fires a 1-tick pulse when the block in front changes.
                  - A repeater set to 2+ ticks is the standard clock delay.""");
        GUIDES.put("biome|bioma",
                """
                Where to find things:
                  - Ancient city: deep dark, usually Y -52.
                  - Woodland mansion: dark forest, often thousands of blocks out.
                  - Ocean monument: deep ocean.
                  - Trial chamber: any Overworld biome, Y -40..0.
                  - Amethyst geode: Y -58..30 anywhere.
                  - Sniffer eggs: warm ocean ruins suspicious sand.""");
        GUIDES.put("armor|armadura|protection",
                "Diamond armour caps at 20 armour points (80%% reduction). Netherite adds knockback resistance and lava "
              + "immunity for dropped items. Protection IV on all four pieces is the biggest single upgrade.");
        GUIDES.put("server|lag|tps|performance|fps",
                "Ask me to tune your settings — I can read your hardware, your frame rate and the mods you have "
              + "loaded, then apply a matching video profile.");
    }

    // ------------------------------------------------------------------ //
    //  Lookup                                                              //
    // ------------------------------------------------------------------ //

    public static String crafting(String query)  { return lookup(CRAFTING, query, "I don't have that recipe memorised. Tell me the exact item name and I'll try again."); }
    public static String enchant(String query)   { return lookup(ENCHANTS, query, "Tell me the item (sword, pickaxe, boots, bow...) and I'll list the best enchantments."); }
    public static String potion(String query)    { return lookup(POTIONS, query, "Start with a water bottle plus nether wart for an Awkward potion, then add the modifier. Which potion did you mean?"); }
    public static String mob(String query)       { return lookup(MOBS, query, "Which mob? I have tactics for creepers, skeletons, endermen, blazes, ghasts, wardens, the wither and the dragon."); }
    public static String guide(String query)     { return lookup(GUIDES, query, ""); }

    /** Search every table at once; used by the rule-based fallback. */
    public static String anything(String query) {
        for (Map<String, String> table : java.util.List.of(GUIDES, CRAFTING, ENCHANTS, POTIONS, MOBS)) {
            String hit = lookup(table, query, "");
            if (!hit.isEmpty()) return hit;
        }
        return "";
    }

    private static String lookup(Map<String, String> table, String query, String fallback) {
        if (query == null || query.isBlank()) return fallback;
        String q = query.toLowerCase(Locale.ROOT);

        // Longest key wins, so "wither skeleton" beats "wither".
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> entry : table.entrySet()) {
            for (String key : entry.getKey().split("\\|")) {
                if (q.contains(key) && key.length() > bestLength) {
                    best = entry.getValue();
                    bestLength = key.length();
                }
            }
        }
        return best != null ? best : fallback;
    }

    // ------------------------------------------------------------------ //
    //  Small calculators                                                   //
    // ------------------------------------------------------------------ //

    /** Total experience points contained in {@code level} levels. */
    public static int totalXpForLevel(int level) {
        int l = Math.max(0, level);
        if (l <= 16) return l * l + 6 * l;
        if (l <= 31) return (int) (2.5 * l * l - 40.5 * l + 360);
        return (int) (4.5 * l * l - 162.5 * l + 2220);
    }

    /** Overworld coordinates converted to their linked Nether coordinates. */
    public static String overworldToNether(int x, int z) {
        return "Nether: (" + Math.floorDiv(x, 8) + ", ~, " + Math.floorDiv(z, 8) + ")";
    }

    /** Nether coordinates converted to their linked Overworld coordinates. */
    public static String netherToOverworld(int x, int z) {
        return "Overworld: (" + (x * 8) + ", ~, " + (z * 8) + ")";
    }

    /** How many items one unit of a given fuel smelts. */
    public static double fuelItems(String fuel) {
        return switch (fuel == null ? "" : fuel.toLowerCase(Locale.ROOT)) {
            case "lava", "lava bucket"      -> 100;
            case "coal block", "block of coal" -> 80;
            case "blaze rod"                -> 12;
            case "coal", "charcoal"         -> 8;
            case "dried kelp block"         -> 20;
            case "planks", "plank"          -> 1.5;
            case "log", "wood"              -> 1.5;
            case "stick"                    -> 0.5;
            case "sapling"                  -> 0.5;
            default                         -> 8;
        };
    }
}
