package com.mycelia.mc.generation;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;

import java.util.Random;

public class MyceliaUndergroundPopulator extends BlockPopulator {

    @Override
    public void populate(World world, Random random, Chunk source) {
        if (random.nextInt(100) < 3) {
            int x = (source.getX() << 4) + random.nextInt(16);
            int z = (source.getZ() << 4) + random.nextInt(16);

            for (int y = 10; y < 50; y++) {
                Block block = world.getBlockAt(x, y, z);
                Block below = world.getBlockAt(x, y - 1, z);

                if (block.getType() == Material.AIR && below.getType().isSolid()) {
                    generateChamber(world, x, y, z, random);
                    break;
                }
            }
        }
    }

    private void generateChamber(World world, int x, int y, int z, Random random) {
        int radius = random.nextInt(2) + 3;

        for (int ox = -radius; ox <= radius; ox++) {
            for (int oz = -radius; oz <= radius; oz++) {
                for (int oy = -1; oy < 4; oy++) {
                    double dist = Math.sqrt(ox * ox + oz * oz);
                    if (dist < radius) {
                        Block target = world.getBlockAt(x + ox, y + oy, z + oz);
                        if (oy == -1) {
                            target.setType(Material.GLOW_LICHEN);
                        } else if (dist > radius - 1.1) {
                            target.setType(random.nextBoolean() ? Material.OBSIDIAN : Material.CRYING_OBSIDIAN);
                        } else {
                            target.setType(Material.AIR);
                        }
                    }
                }
            }
        }

        world.getBlockAt(x, y, z).setType(Material.AMETHYST_CLUSTER);
    }
}
