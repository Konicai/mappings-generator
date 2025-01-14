package org.geysermc.generator.state.type;

import org.apache.commons.lang3.tuple.Pair;
import org.geysermc.generator.state.StateMapper;
import org.geysermc.generator.state.StateRemapper;

@StateRemapper(value = "facing", blockRegex = "^minecraft:small_dripleaf")
public class SmallDripLeafDirectionMapper extends StateMapper<Integer> {

    @Override
    public Pair<String, Integer> translateState(String fullIdentifier, String value) {
        // Bedrock's small drip leaves are rotated clockwise once for the same direction, so these values are shifted around
        return Pair.of("direction", switch (value) {
            case "south" -> 3;
            case "west" -> 0;
            case "north" -> 1;
            case "east" -> 2;
            default -> throw new RuntimeException("Could not determine small dripleaf direction!");
        });
    }
}
