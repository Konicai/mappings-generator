package org.geysermc.generator;

import com.google.common.collect.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.nukkitx.nbt.NBTInputStream;
import com.nukkitx.nbt.NbtList;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtType;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.apache.commons.lang3.tuple.Pair;
import org.geysermc.generator.state.StateMapper;
import org.geysermc.generator.state.StateRemapper;
import org.reflections.Reflections;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class MappingsGenerator {

    public static final Map<String, BlockEntry> BLOCK_ENTRIES = new HashMap<>();
    public static final Map<String, ItemEntry> ITEM_ENTRIES = new HashMap<>();
    public static final Map<String, SoundEntry> SOUND_ENTRIES = new HashMap<>();
    public static final List<String> VALID_BEDROCK_ITEMS = new ArrayList<>();
    public static final Map<String, String> JAVA_TO_BEDROCK_ITEM_OVERRIDE = new HashMap<>();
    public static final Map<String, List<String>> STATES = new HashMap<>();
    private static final List<String> POTTABLE_BLOCK_IDENTIFIERS = Arrays.asList("minecraft:dandelion", "minecraft:poppy",
            "minecraft:blue_orchid", "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
            "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
            "minecraft:wither_rose", "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling", "minecraft:jungle_sapling",
            "minecraft:acacia_sapling", "minecraft:dark_oak_sapling", "minecraft:red_mushroom", "minecraft:brown_mushroom", "minecraft:fern",
            "minecraft:dead_bush", "minecraft:cactus", "minecraft:bamboo", "minecraft:crimson_fungus", "minecraft:warped_fungus",
            "minecraft:crimson_roots", "minecraft:warped_roots", "minecraft:azalea", "minecraft:flowering_azalea");
    // This ends up in collision.json
    // collision_index in blocks.json refers to this to prevent duplication
    // This helps to reduce file size
    public static final List<List<List<Double>>> COLLISION_LIST = Lists.newArrayList();

    private static final Gson GSON = new Gson();

    private final Multimap<String, StateMapper<?>> stateMappers = HashMultimap.create();

    public void generateBlocks() {
        Reflections ref = new Reflections("org.geysermc.generator.state.type");
        for (Class<?> clazz : ref.getTypesAnnotatedWith(StateRemapper.class)) {
            try {
                StateMapper<?> stateMapper = (StateMapper<?>) clazz.newInstance();
                this.stateMappers.put(clazz.getAnnotation(StateRemapper.class).value(), stateMapper);
            } catch (InstantiationException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        try {
            NbtList<NbtMap> palette;
            File blockPalette = new File("palettes/blockpalette.nbt");
            if (!blockPalette.exists()) {
                System.out.println("Could not find block palette (blockpalette.nbt), please refer to the README in the palettes directory.");
                return;
            }

            try {
                InputStream stream = new FileInputStream(blockPalette);

                try (NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)))) {
                    NbtMap ret = (NbtMap) nbtInputStream.readTag();
                    palette = (NbtList<NbtMap>) ret.getList("blocks", NbtType.COMPOUND);
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to get blocks from block palette", e);
            }

            File mappings = new File("mappings/blocks.json");
            File collision = new File("mappings/collision.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, BlockEntry>>() {}.getType();
                Map<String, BlockEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                BLOCK_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            for (NbtMap entry : palette) {
                String identifier = entry.getString("name");
                if (!STATES.containsKey(identifier)) {
                    NbtMap states = entry.getCompound("states");
                    List<String> stateKeys = new ArrayList<>(states.keySet());
                    // ignore some useless keys
                    stateKeys.remove("stone_slab_type");
                    STATES.put(identifier, stateKeys);
                }
            }
            // Some State Corrections
            STATES.put("minecraft:attached_pumpkin_stem", Arrays.asList("growth", "facing_direction"));
            STATES.put("minecraft:attached_melon_stem", Arrays.asList("growth", "facing_direction"));

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            FileWriter writer = new FileWriter(mappings);
            JsonObject rootObject = new JsonObject();

            for (BlockState blockState : getAllStates()) {
                rootObject.add(blockStateToString(blockState), getRemapBlock(blockState, blockStateToString(blockState)));
            }

            builder.create().toJson(rootObject, writer);
            writer.close();

            // Write collision types
            writer = new FileWriter(collision);
            builder.create().toJson(COLLISION_LIST, writer);
            writer.close();

            System.out.println("Some block states need to be manually mapped, please search for MANUALMAP in blocks.json, if there are no occurrences you do not need to do anything.");
            System.out.println("Finished block writing process!");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void generateItems() {
        try {
            File mappings = new File("mappings/items.json");
            File itemPalette = new File("palettes/runtime_item_states.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }
            if (!itemPalette.exists()) {
                System.out.println("Could not find item palette (runtime_item_states.json), please refer to the README in the palettes directory.");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, ItemEntry>>() {}.getType();
                Map<String, ItemEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                ITEM_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            try {
                Type listType = new TypeToken<List<PaletteItemEntry>>(){}.getType();
                List<PaletteItemEntry> entries = GSON.fromJson(new FileReader(itemPalette), listType);
                entries.forEach(item -> VALID_BEDROCK_ITEMS.add(item.getIdentifier()));
                // Fix some discrepancies - key is the Java string and value is the Bedrock string

                // Conflicts
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:grass", "minecraft:tallgrass"); // Conflicts with grass block
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:grass_block", "minecraft:grass");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:map", "minecraft:empty_map"); // Conflicts with filled map
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:melon", "minecraft:melon_block"); // Conflicts with melon slice
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:nether_brick", "minecraft:netherbrick"); // This is the item; the block conflicts
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:nether_bricks", "minecraft:nether_brick");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:snow", "minecraft:snow_layer"); // Conflicts with snow block
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:snow_block", "minecraft:snow");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:stone_stairs", "minecraft:normal_stone_stairs"); // Conflicts with cobblestone stairs
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:cobblestone_stairs", "minecraft:stone_stairs");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:stonecutter", "minecraft:stonecutter_block"); // Conflicts with, surprisingly, the OLD MCPE stonecutter

                // Changed names
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:glow_item_frame", "minecraft:glow_frame");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:item_frame", "minecraft:frame");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:oak_door", "minecraft:wooden_door");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:shulker_box", "minecraft:undyed_shulker_box");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:small_dripleaf", "minecraft:small_dripleaf_block");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:waxed_copper_block", "minecraft:waxed_copper");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:zombified_piglin_spawn_egg","minecraft:zombie_pigman_spawn_egg");

                // Item replacements
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:globe_banner_pattern", "minecraft:banner_pattern");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:trader_llama_spawn_egg", "minecraft:llama_spawn_egg");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:sculk_sensor", "minecraft:info_update"); // soon
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            JsonObject rootObject = new JsonObject();

            for (ResourceLocation key : Registry.ITEM.keySet()) {
                Optional<Item> item = Registry.ITEM.getOptional(key);
                item.ifPresent(value ->
                        rootObject.add(key.getNamespace() + ":" + key.getPath(), getRemapItem(
                                key.getNamespace() + ":" + key.getPath(), Block.byItem(value), value.getMaxStackSize())));
            }

            FileWriter writer = new FileWriter(mappings);
            builder.create().toJson(rootObject, writer);
            writer.close();
            System.out.println("Finished item writing process!");

            // Check for duplicate mappings
            Map<JsonElement, String> itemDuplicateCheck = new HashMap<>();
            for (Map.Entry<String, JsonElement> object : rootObject.entrySet()) {
                if (itemDuplicateCheck.containsKey(object.getValue())) {
                    System.out.println("Possible duplicate items (" + object.getKey() + " and " + itemDuplicateCheck.get(object.getValue()) + ") in mappings: " + object.getValue());
                } else {
                    itemDuplicateCheck.put(object.getValue(), object.getKey());
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void generateSounds() {
        try {
            File mappings = new File("mappings/sounds.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, SoundEntry>>() {}.getType();
                Map<String, SoundEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                SOUND_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            FileWriter writer = new FileWriter(mappings);
            JsonObject rootObject = new JsonObject();

            for (ResourceLocation key : Registry.SOUND_EVENT.keySet()) {
                Optional<SoundEvent> sound = Registry.SOUND_EVENT.getOptional(key);
                sound.ifPresent(soundEvent -> {
                    SoundEntry soundEntry = SOUND_ENTRIES.get(key.getPath());
                    if (soundEntry == null) {
                        soundEntry = new SoundEntry(key.getPath(), "", -1, null, false);
                    }
                    JsonObject object = (JsonObject) GSON.toJsonTree(soundEntry);
                    if (soundEntry.getExtraData() <= 0 && !key.getPath().equals("block.note_block.harp")) {
                        object.remove("extra_data");
                    }
                    if (soundEntry.getIdentifier() == null || soundEntry.getIdentifier().isEmpty()) {
                        object.remove("identifier");
                    }
                    if (!soundEntry.isLevelEvent()) {
                        object.remove("level_event");
                    }
                    rootObject.add(key.getPath(), object);
                });
            }

            builder.create().toJson(rootObject, writer);
            writer.close();
            System.out.println("Finished sound writing process!");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public JsonObject getRemapBlock(BlockState state, String identifier) {
        JsonObject object = new JsonObject();
        BlockEntry blockEntry = BLOCK_ENTRIES.get(identifier);
        String trimmedIdentifier = identifier.split("\\[")[0];

        String bedrockIdentifier;
        // All walls before 1.16 use the same identifier (cobblestone_wall)
        if (trimmedIdentifier.endsWith("_wall") && isSensibleWall(trimmedIdentifier)) {
            // Reset any existing mapping to cobblestone wall
            bedrockIdentifier = trimmedIdentifier;
        } else if (trimmedIdentifier.endsWith("_wall")) {
            bedrockIdentifier = "minecraft:cobblestone_wall";
        } else if (trimmedIdentifier.equals("minecraft:powered_rail")) {
            bedrockIdentifier = "minecraft:golden_rail";
        } else if (trimmedIdentifier.equals("minecraft:light")) {
            bedrockIdentifier = "minecraft:light_block";
        } else if (trimmedIdentifier.equals("minecraft:dirt_path")) {
            bedrockIdentifier = "minecraft:grass_path";
        } else if (trimmedIdentifier.equals("minecraft:small_dripleaf")) {
            bedrockIdentifier = "minecraft:small_dripleaf_block";
        } else if (trimmedIdentifier.equals("minecraft:big_dripleaf_stem")) {
            // Includes the head and stem
            bedrockIdentifier = "minecraft:big_dripleaf";
        } else if (trimmedIdentifier.equals("minecraft:flowering_azalea_leaves")) {
            bedrockIdentifier = "minecraft:azalea_leaves_flowered";
        } else if (trimmedIdentifier.equals("minecraft:rooted_dirt")) {
            bedrockIdentifier = "minecraft:dirt_with_roots";
        } else if (trimmedIdentifier.contains("cauldron")) {
            bedrockIdentifier = "minecraft:cauldron";
        } else if (trimmedIdentifier.equals("minecraft:waxed_copper_block")) {
            bedrockIdentifier = "minecraft:waxed_copper";
        } else if (trimmedIdentifier.endsWith("_slab") && identifier.contains("type=double")) {
            // Fixes 1.16 double slabs
            if (blockEntry != null) {
                if (blockEntry.getBedrockIdentifier().contains("double") && !blockEntry.getBedrockIdentifier().contains("copper")) {
                    bedrockIdentifier = blockEntry.getBedrockIdentifier();
                } else {
                    bedrockIdentifier = formatDoubleSlab(trimmedIdentifier);
                }
            } else {
                bedrockIdentifier = formatDoubleSlab(trimmedIdentifier);
            }
        } else {
            // Default to trimmed identifier, or the existing identifier
            bedrockIdentifier = blockEntry != null ? blockEntry.getBedrockIdentifier() : trimmedIdentifier;
        }
        object.addProperty("bedrock_identifier", bedrockIdentifier);

        object.addProperty("block_hardness", state.getDestroySpeed(null, null));
        List<List<Double>> collisionBoxes = Lists.newArrayList();
        try {
            state.getCollisionShape(null, null).toAabbs().forEach(item -> {
                List<Double> coordinateList = Lists.newArrayList();
                // Convert Box class to an array of coordinates
                // They need to be converted from min/max coordinates to centres and sizes
                coordinateList.add(item.minX + ((item.maxX - item.minX) / 2));
                coordinateList.add(item.minY + ((item.maxY - item.minY) / 2));
                coordinateList.add(item.minZ + ((item.maxZ - item.minZ) / 2));

                coordinateList.add(item.maxX - item.minX);
                coordinateList.add(item.maxY - item.minY);
                coordinateList.add(item.maxZ - item.minZ);

                collisionBoxes.add(coordinateList);
            });
        } catch (NullPointerException e) {
            // Fallback to empty collision when the position is needed to calculate it
        }

        if (!COLLISION_LIST.contains(collisionBoxes)) {
            COLLISION_LIST.add(collisionBoxes);
        }
        // This points to the index of the collision in collision.json
        object.addProperty("collision_index", COLLISION_LIST.lastIndexOf(collisionBoxes));

        try {
            // Ignore water, lava, and fire because players can't pick them
            if (!trimmedIdentifier.equals("minecraft:water") && !trimmedIdentifier.equals("minecraft:lava") && !trimmedIdentifier.equals("minecraft:fire")) {
                Block block = state.getBlock();
                ItemStack pickStack = block.getCloneItemStack(null, null, state);
                String pickStackIdentifier = Registry.ITEM.getKey(pickStack.getItem()).toString();
                if (!pickStackIdentifier.equals(trimmedIdentifier)) {
                    object.addProperty("pick_item", pickStackIdentifier);
                }
            }
        } catch (NullPointerException e) {
            // The block's pick item depends on a block entity.
            // Banners and Shulker Boxes both depend on the block entity.
        }
        object.addProperty("can_break_with_hand", !state.requiresCorrectToolForDrops());
        // Removes nbt tags from identifier
        // Add tool type for blocks that use shears or sword
        if (trimmedIdentifier.contains("_bed")) {
            String woolid = trimmedIdentifier.replace("minecraft:", "");
            woolid = woolid.split("_bed")[0].toUpperCase();
            object.addProperty("bed_color", DyeColor.valueOf(woolid).getId());
        } else if (trimmedIdentifier.contains("head") && !trimmedIdentifier.contains("piston") || trimmedIdentifier.contains("skull")) {
            if (!trimmedIdentifier.contains("wall")) {
                int rotationId = Integer.parseInt(identifier.substring(identifier.indexOf("rotation=") + 9, identifier.indexOf("]")));
                object.addProperty("skull_rotation", rotationId);
            }
            if (trimmedIdentifier.contains("wither_skeleton")) {
                object.addProperty("variation", 1);
            } else if (trimmedIdentifier.contains("skeleton")) {
                object.addProperty("variation", 0);
            } else if (trimmedIdentifier.contains("zombie")) {
                object.addProperty("variation", 2);
            } else if (trimmedIdentifier.contains("player")) {
                object.addProperty("variation", 3);
            } else if (trimmedIdentifier.contains("creeper")) {
                object.addProperty("variation", 4);
            } else if (trimmedIdentifier.contains("dragon")) {
                object.addProperty("variation", 5);
            }
        } else if (trimmedIdentifier.contains("_banner")) {
            String woolid = trimmedIdentifier.replace("minecraft:", "");
            woolid = woolid.split("_banner")[0].split("_wall")[0].toUpperCase();
            object.addProperty("banner_color", DyeColor.valueOf(woolid).getId());
        } else if (trimmedIdentifier.contains("note_block")) {
            int notepitch = Integer.parseInt(identifier.substring(identifier.indexOf("note=") + 5, identifier.indexOf(",powered")));
            object.addProperty("note_pitch", notepitch);
        } else if (trimmedIdentifier.contains("shulker_box")) {
            object.addProperty("shulker_direction", getDirectionInt(identifier.substring(identifier.indexOf("facing=") + 7, identifier.indexOf("]"))));
        } else if (trimmedIdentifier.contains("chest") && (identifier.contains("type="))) {
            if (identifier.contains("type=left")) {
                object.addProperty("double_chest_position", "left");
            } else if (identifier.contains("type=right")) {
                object.addProperty("double_chest_position", "right");
            }
            if (identifier.contains("north")) {
                object.addProperty("z", false);
            } else if (identifier.contains("south")) {
                object.addProperty("z", true);
            } else if (identifier.contains("east")) {
                object.addProperty("x", true);
            } else if (identifier.contains("west")) {
                object.addProperty("x", false);
            }
        }

        JsonElement bedrockStates = blockEntry != null ? blockEntry.getBedrockStates() : null;
        if (bedrockStates == null) {
            bedrockStates = new JsonObject();
        }

        JsonObject statesObject = bedrockStates.getAsJsonObject();
        if (blockEntry != null && STATES.get(blockEntry.getBedrockIdentifier()) != null) {
            // Prevent ConcurrentModificationException
            List<String> toRemove = new ArrayList<>();
            // Since we now rely on block states being exact after 1.16.100, we need to remove any old states
            for (Map.Entry<String, JsonElement> entry : statesObject.entrySet()) {
                List<String> states = STATES.get(blockEntry.getBedrockIdentifier());
                if (!states.contains(entry.getKey()) &&
                        !entry.getKey().contains("stone_slab_type")) { // Ignore the stone slab types since we ignore them above
                    toRemove.add(entry.getKey());
                }
            }
            for (String key : toRemove) {
                statesObject.remove(key);
            }
        } else if (blockEntry != null) {
            System.out.println("States for " + blockEntry.getBedrockIdentifier() + " not found!");
        } else {
            System.out.println("Block entry for " + blockStateToString(state) + " is null?");
        }
        String[] states = identifier.contains("[") ? identifier.substring(identifier.lastIndexOf("[") + 1).replace("]", "").split(",") : new String[0];
        for (String javaState : states) {
            String key = javaState.split("=")[0];
            if (!this.stateMappers.containsKey(key)) {
                continue;
            }
            Collection<StateMapper<?>> stateMappers = this.stateMappers.get(key);

            stateLoop:
            for (StateMapper<?> stateMapper : stateMappers) {
                String[] blockRegex = stateMapper.getClass().getAnnotation(StateRemapper.class).blockRegex();
                if (blockRegex.length != 0) {
                    for (String regex : blockRegex) {
                        if (!trimmedIdentifier.matches(regex)) {
                            continue stateLoop;
                        }
                    }
                }
                String value = javaState.split("=")[1];
                Pair<String, ?> bedrockState = stateMapper.translateState(identifier, value);
                if (bedrockState.getValue() instanceof Number) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, Number.class));
                }
                if (bedrockState.getValue() instanceof Boolean) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, Boolean.class));
                }
                if (bedrockState.getValue() instanceof String) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, String.class));
                }
            }
        }

        if (trimmedIdentifier.equals("minecraft:glow_lichen")) {
            int bitset = 0;
            List<String> statesList = Arrays.asList(states);
            if (statesList.contains("down=true")) {
                bitset |= 1;
            }
            if (statesList.contains("up=true")) {
                bitset |= 1 << 1;
            }
            if (statesList.contains("north=true")) {
                bitset |= 1 << 2;
            }
            if (statesList.contains("south=true")) {
                bitset |= 1 << 3;
            }
            if (statesList.contains("west=true")) {
                bitset |= 1 << 4;
            }
            if (statesList.contains("east=true")) {
                bitset |= 1 << 5;
            }
            statesObject.addProperty("multi_face_direction_bits", bitset);
        }

        else if (trimmedIdentifier.endsWith("_cauldron")) {
            statesObject.addProperty("cauldron_liquid", trimmedIdentifier.replace("minecraft:", "").replace("_cauldron", ""));
            if (trimmedIdentifier.equals("minecraft:lava_cauldron")) {
                // Only one fill level option
                statesObject.addProperty("fill_level", 6);
            }
        }

        else if (trimmedIdentifier.contains("big_dripleaf")) {
            boolean isHead = !trimmedIdentifier.contains("stem");
            statesObject.addProperty("big_dripleaf_head", isHead);
            if (!isHead) {
                statesObject.addProperty("big_dripleaf_tilt", "none");
            }
        }

        String stateIdentifier = trimmedIdentifier;
        if (trimmedIdentifier.endsWith("_wall") && !isSensibleWall(trimmedIdentifier)) {
            stateIdentifier = "minecraft:cobblestone_wall";
        }

        List<String> stateKeys = STATES.get(stateIdentifier);
        if (stateKeys != null) {
            stateKeys.forEach(key -> {
                if (trimmedIdentifier.contains("minecraft:shulker_box")) return;
                if (!statesObject.has(key)) {
                    statesObject.addProperty(key, "MANUALMAP");
                }
            });
        }

        // No more manual pottable because I'm angry I don't care how bad the list looks
        if (POTTABLE_BLOCK_IDENTIFIERS.contains(trimmedIdentifier)) {
            object.addProperty("pottable", true);
        }

        if (statesObject.entrySet().size() != 0) {
            if (statesObject.has("wall_block_type") && isSensibleWall(trimmedIdentifier)) {
                statesObject.getAsJsonObject().remove("wall_block_type");
            }
            object.add("bedrock_states", statesObject);
        }

        return object;
    }

    public JsonObject getRemapItem(String identifier, Block block, int stackSize) {
        JsonObject object = new JsonObject();
        if (ITEM_ENTRIES.containsKey(identifier)) {
            ItemEntry itemEntry = ITEM_ENTRIES.get(identifier);
            // Deal with items that we replace
            String bedrockIdentifier = switch (identifier.replace("minecraft:", "")) {
                case "knowledge_book" -> "book";
                case "tipped_arrow", "spectral_arrow" -> "arrow";
                case "debug_stick" -> "stick";
                case "furnace_minecart" -> "hopper_minecart";
                default -> JAVA_TO_BEDROCK_ITEM_OVERRIDE.getOrDefault(identifier, itemEntry.getBedrockIdentifier()).replace("minecraft:", "");
            };

            if (identifier.endsWith("banner")) { // Don't include banner patterns
                bedrockIdentifier = "banner";
            } else if (identifier.endsWith("bed")) {
                bedrockIdentifier = "bed";
            } else if (identifier.endsWith("_skull") || (identifier.endsWith("_head"))) {
                bedrockIdentifier = "skull";
            } else if (identifier.endsWith("_shulker_box")) {
                // Colored shulker boxes only
                bedrockIdentifier = "shulker_box";
            }
            object.addProperty("bedrock_identifier", "minecraft:" + bedrockIdentifier);

            if (!VALID_BEDROCK_ITEMS.contains("minecraft:" + bedrockIdentifier)) {
                System.out.println(bedrockIdentifier + " not found in Bedrock runtime item states!");
            }

            boolean isBlock = block != Blocks.AIR;
            object.addProperty("bedrock_data", isBlock ? itemEntry.getBedrockData() : 0);
            if (isBlock) {
                BlockState state = block.defaultBlockState();
                // Fix some render issues - :microjang:
                if (block instanceof WallBlock) {
                    String blockIdentifier = Registry.BLOCK.getKey(block).toString();
                    if (!isSensibleWall(blockIdentifier)) { // Blackstone renders fine
                        // Required for the item to render with the correct type (sandstone, stone brick, etc)
                        state = state.setValue(WallBlock.UP, false);
                    }
                }
                object.addProperty("blockRuntimeId", Block.getId(state));
            }
        } else {
            object.addProperty("bedrock_identifier", "minecraft:update_block");
            object.addProperty("bedrock_data", 0);
        }
        if (stackSize != 64) {
            object.addProperty("stack_size", stackSize);
        }
        String[] toolTypes = {"sword", "shovel", "pickaxe", "axe", "shears", "hoe"};
        String[] identifierSplit = identifier.split(":")[1].split("_");
        if (identifierSplit.length > 1) {
            Optional<String> optToolType = Arrays.stream(toolTypes).parallel().filter(identifierSplit[1]::equals).findAny();
            if (optToolType.isPresent()) {
                object.addProperty("tool_type", optToolType.get());
                object.addProperty("tool_tier", identifierSplit[0]);
            }
        } else {
            Optional<String> optToolType = Arrays.stream(toolTypes).parallel().filter(identifierSplit[0]::equals).findAny();
            optToolType.ifPresent(s -> object.addProperty("tool_type", s));
        }

        return object;
    }

    public List<BlockState> getAllStates() {
        List<BlockState> states = new ArrayList<>();
        Registry.BLOCK.forEach(block -> states.addAll(block.getStateDefinition().getPossibleStates()));
        return states.stream().sorted(Comparator.comparingInt(Block::getId)).collect(Collectors.toList());
    }

    private String blockStateToString(BlockState blockState) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(Registry.BLOCK.getKey(blockState.getBlock()).toString());
        if (!blockState.getValues().isEmpty()) {
            stringBuilder.append('[');
            stringBuilder.append(blockState.getValues().entrySet().stream().map(PROPERTY_MAP_PRINTER).collect(Collectors.joining(",")));
            stringBuilder.append(']');
        }
        return stringBuilder.toString();
    }

    private static final Function<Map.Entry<Property<?>, Comparable<?>>, String> PROPERTY_MAP_PRINTER = new Function<>() {

        public String apply(Map.Entry<Property<?>, Comparable<?>> entry) {
            if (entry == null) {
                return "<NULL>";
            } else {
                Property<?> lv = entry.getKey();
                return lv.getName() + "=" + this.nameValue(lv, entry.getValue());
            }
        }

        private <T extends Comparable<T>> String nameValue(Property<T> arg, Comparable<?> comparable) {
            return arg.getName((T) comparable);
        }
    };

    /**
     * Converts a Java edition direction string to an byte for Bedrock edition
     * Designed for Shulker boxes, may work for other things
     *
     * @param direction The direction string
     * @return Converted direction byte
     */
    private static byte getDirectionInt(String direction) {
        return (byte) switch (direction) {
            case "down" -> 0;
            case "north" -> 2;
            case "south" -> 3;
            case "west" -> 4;
            case "east" -> 5;
            default -> 1;
        };

    }

    /**
     * @return true if this wall can be treated normally and not stupidly
     */
    private static boolean isSensibleWall(String identifier) {
        return identifier.contains("blackstone") || identifier.contains("deepslate");
    }

    /**
     * @return the correct double slab identifier for Bedrock
     */
    private static String formatDoubleSlab(String identifier) {
        if (identifier.contains("double")) {
            return identifier;
        }

        if (identifier.contains("cut_copper")) {
            return identifier.replace("cut", "double_cut");
        }
        return identifier.replace("_slab", "_double_slab");
    }
}
