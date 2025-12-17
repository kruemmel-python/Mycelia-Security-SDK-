package com.mycelia.mc.generation;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.util.noise.SimplexNoiseGenerator;

import java.util.Random;

public class MyceliaChunkGenerator extends ChunkGenerator {

    private final long seed;
    private final Material baseBlock;
    private final Material surfaceBlock;
    private final int seaLevel;
    private final double amplitude;
    private final double scale;
    private final SimplexNoiseGenerator noiseGenerator;

    private MyceliaChunkGenerator(long seed, Material baseBlock, Material surfaceBlock, int seaLevel, double amplitude, double scale) {
        this.seed = seed;
        this.baseBlock = baseBlock;
        this.surfaceBlock = surfaceBlock;
        this.seaLevel = seaLevel;
        this.amplitude = amplitude;
        this.scale = scale;
        this.noiseGenerator = new SimplexNoiseGenerator(seed);
    }

    public static MyceliaChunkGenerator fromConfig(FileConfiguration config, long seed) {
        String baseBlockName = config.getString("world.baseBlock", "STONE");
        String surfaceBlockName = config.getString("world.surfaceBlock", "GRASS_BLOCK");
        int seaLevel = config.getInt("world.seaLevel", 62);
        double amplitude = config.getDouble("world.amplitude", 24.0);
        double scale = config.getDouble("world.scale", 0.015);

        Material baseBlock = Material.matchMaterial(baseBlockName.toUpperCase());
        if (baseBlock == null) {
            baseBlock = Material.STONE;
        }
        Material surfaceBlock = Material.matchMaterial(surfaceBlockName.toUpperCase());
        if (surfaceBlock == null) {
            surfaceBlock = Material.GRASS_BLOCK;
        }

        return new MyceliaChunkGenerator(seed, baseBlock, surfaceBlock, seaLevel, amplitude, scale);
    }

    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        ChunkData chunkData = createChunkData(world);
        int worldXBase = chunkX << 4;
        int worldZBase = chunkZ << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = worldXBase + x;
                int worldZ = worldZBase + z;
                double noise = noiseGenerator.noise(worldX * scale, worldZ * scale);
                int height = (int) Math.round((noise * amplitude) + seaLevel);
                buildColumn(chunkData, x, z, height);
            }
        }

        return chunkData;
    }

    private void buildColumn(ChunkData data, int x, int z, int height) {
        if (height < 1) {
            height = 1;
        }
        int surfaceY = Math.min(height, data.getMaxHeight() - 1);
        for (int y = 0; y <= surfaceY; y++) {
            if (y == surfaceY) {
                data.setBlock(x, y, z, surfaceBlock);
            } else {
                data.setBlock(x, y, z, baseBlock);
            }
        }
    }

    public long seed() {
        return seed;
    }
}
