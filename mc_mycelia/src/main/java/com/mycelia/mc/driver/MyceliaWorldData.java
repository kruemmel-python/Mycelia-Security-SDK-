package com.mycelia.mc.driver;

public record MyceliaWorldData(
        long seed,
        String baseBlock,
        String surfaceBlock,
        String oreBlock,
        double scale,
        int seaLevel
) {
}
