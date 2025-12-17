package com.mycelia.mc.generation;

import com.mycelia.mc.driver.MyceliaWorldData;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.util.noise.SimplexNoiseGenerator;
import java.util.Random;

public class MyceliaChunkGenerator extends ChunkGenerator {

    private final MyceliaWorldData data;
    private final SimplexNoiseGenerator terrainNoise;
    private final SimplexNoiseGenerator oreNoise;
    private final Material baseMat;
    private final Material surfaceMat;
    private final Material oreMat;
    private final int seaLevel;

    public MyceliaChunkGenerator(MyceliaWorldData data) {
        this.data = data;
        this.terrainNoise = new SimplexNoiseGenerator(data.seed());
        this.oreNoise = new SimplexNoiseGenerator(data.seed() ^ 0xCAFEEBABEL);
        this.baseMat = materialOrDefault(data.baseBlock(), Material.STONE);
        this.surfaceMat = materialOrDefault(data.surfaceBlock(), Material.MYCELIUM);
        this.oreMat = materialOrDefault(data.oreBlock(), Material.AMETHYST_BLOCK);
        this.seaLevel = data.seaLevel();
    }

    @Override
    public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
        ChunkData chunk = createChunkData(world);
        double scale = data.scale();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean surfacePlaced = false;
                for (int y = 120; y >= world.getMinHeight(); y--) {
                    double worldX = (chunkX << 4) + x;
                    double worldZ = (chunkZ << 4) + z;

                    double density = terrainNoise.noise(worldX * scale, y * (scale * 1.5), worldZ * scale);
                    double finalValue = density + (1.0 - (y / 85.0));

                    if (finalValue > 0.5) {
                        double oreValue = oreNoise.noise(worldX * scale * 4, y * scale * 4, worldZ * scale * 4);
                        if (oreValue > 0.8) {
                            chunk.setBlock(x, y, z, oreMat);
                        } else if (!surfacePlaced && y > seaLevel) {
                            chunk.setBlock(x, y, z, surfaceMat);
                            surfacePlaced = true;
                        } else {
                            chunk.setBlock(x, y, z, baseMat);
                        }
                    } else if (y < seaLevel) {
                        chunk.setBlock(x, y, z, Material.WATER);
                    }
                }
            }
        }
        return chunk;
    }

    @Override
    public java.util.List<org.bukkit.generator.BlockPopulator> getDefaultPopulators(World world) {
        return java.util.List.of(new MyceliaStructurePopulator());
    }

    private Material materialOrDefault(String name, Material fallback) {
        Material material = Material.matchMaterial(name);
        return material != null ? material : fallback;
    }
}
