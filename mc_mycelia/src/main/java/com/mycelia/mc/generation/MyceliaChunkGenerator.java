package com.mycelia.mc.generation;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.TreeType;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.util.noise.SimplexNoiseGenerator;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.List;
import java.util.Random;

public class MyceliaChunkGenerator extends ChunkGenerator {

    private final long seed;
    private final Material baseBlock;
    private final Material surfaceBlock;
    private final int seaLevel;
    private final double scale;
    private final SimplexNoiseGenerator terrainNoise;
    private final SimplexNoiseGenerator oreNoise;

    private MyceliaChunkGenerator(long seed, Material base, Material surface, int sea, double scale) {
        this.seed = seed;
        this.baseBlock = base;
        this.surfaceBlock = surface;
        this.seaLevel = sea;
        this.scale = scale;
        this.terrainNoise = new SimplexNoiseGenerator(seed);
        // Zweiter Generator mit versetztem Seed für Erze/Adern
        this.oreNoise = new SimplexNoiseGenerator(seed ^ 0xCAFEEBABEL);
    }

    public static MyceliaChunkGenerator fromConfig(FileConfiguration config, long seed) {
        return new MyceliaChunkGenerator(
            seed,
            Material.matchMaterial(config.getString("world.baseBlock", "STONE")),
            Material.matchMaterial(config.getString("world.surfaceBlock", "MYCELIUM")),
            config.getInt("world.seaLevel", 40),
            config.getDouble("world.scale", 0.03)
        );
    }

    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        ChunkData chunkData = createChunkData(world);
        int worldXBase = chunkX << 4;
        int worldZBase = chunkZ << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean surfacePlaced = false;

                // Wir generieren von oben nach unten für 3D-Dichte
                for (int y = 120; y >= world.getMinHeight(); y--) {
                    double worldX = worldXBase + x;
                    double worldZ = worldZBase + z;

                    // 1. Terrain-Berechnung (Density)
                    double density = terrainNoise.noise(worldX * scale, y * (scale * 1.5), worldZ * scale);
                    double gradient = 1.0 - ((double) y / 80.0); // Dichte nimmt nach oben ab
                    double finalValue = density + gradient;

                    if (finalValue > 0.5) {
                        // 2. Ressourcen-Check (Adern)
                        double v = oreNoise.noise(worldX * scale * 3, y * scale * 3, worldZ * scale * 3);
                        
                        if (v > 0.75) {
                            chunkData.setBlock(x, y, z, Material.AMETHYST_BLOCK);
                        } else if (!surfacePlaced && y >= seaLevel) {
                            chunkData.setBlock(x, y, z, surfaceBlock);
                            surfacePlaced = true;
                        } else {
                            chunkData.setBlock(x, y, z, baseBlock);
                        }
                    } else if (y < seaLevel) {
                        // Wasser/Myzel-Sumpf unter dem Meeresspiegel
                        chunkData.setBlock(x, y, z, Material.WATER);
                    }
                }
            }
        }
        return chunkData;
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new BlockPopulator() {
            @Override
            public void populate(World world, Random random, Chunk source) {
                // 5% Chance auf einen Riesenpilz pro Chunk
                if (random.nextInt(100) < 5) {
                    int x = (source.getX() << 4) + random.nextInt(16);
                    int z = (source.getZ() << 4) + random.nextInt(16);
                    int y = world.getHighestBlockYAt(x, z);

                    if (y > seaLevel) {
                        world.generateTree(world.getBlockAt(x, y + 1, z).getLocation(), 
                            random.nextBoolean() ? TreeType.RED_MUSHROOM : TreeType.BROWN_MUSHROOM);
                    }
                }
            }
        });
    }
}
