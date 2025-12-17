package com.mycelia.mc.generation;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;

import java.util.Random;

public class MyceliaStructurePopulator extends BlockPopulator {

    @Override
    public void populate(World world, Random random, Chunk source) {
        if (random.nextInt(100) < 2) {
            int x = (source.getX() << 4) + random.nextInt(16);
            int z = (source.getZ() << 4) + random.nextInt(16);
            int y = world.getHighestBlockYAt(x, z);

            if (y > 45 && world.getBlockAt(x, y, z).getType() != Material.WATER) {
                createMushroomDungeon(world, x, y, z, random);
            }
        }
    }

    private void createMushroomDungeon(World world, int x, int y, int z, Random random) {
        int size = random.nextInt(3) + 3; // 3x3 bis 5x5

        for (int ox = -size; ox <= size; ox++) {
            for (int oz = -size; oz <= size; oz++) {
                for (int oy = 0; oy < 4; oy++) {
                    Material mat = Material.AIR;

                    if (Math.abs(ox) == size || Math.abs(oz) == size) {
                        mat = Material.MUSHROOM_STEM;
                    } else if (oy == 0) {
                        mat = Material.CHISELED_DEEPSLATE;
                    } else if (oy == 3) {
                        mat = Material.SHROOMLIGHT;
                    }

                    if (mat != Material.AIR) {
                        world.getBlockAt(x + ox, y + oy, z + oz).setType(mat);
                    }
                }
            }
        }

        world.getBlockAt(x, y + 1, z).setType(Material.LOOM);
    }
}
