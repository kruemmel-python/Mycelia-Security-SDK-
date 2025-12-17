package com.mycelia.mc.command;

import com.mycelia.mc.MyceliaWorldPlugin;
import com.mycelia.mc.driver.MyceliaDriver;
import com.mycelia.mc.generation.MyceliaChunkGenerator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.util.Optional;
import java.util.stream.Collectors;

public class MyceliaWorldCommand implements CommandExecutor {

    private final MyceliaWorldPlugin plugin;
    private final MyceliaDriver driver;

    public MyceliaWorldCommand(MyceliaWorldPlugin plugin, MyceliaDriver driver) {
        this.plugin = plugin;
        this.driver = driver;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/myceliaworld list");
            sender.sendMessage("§e/myceliaworld tp <world>");
            sender.sendMessage("§e/myceliaworld remove <world> [--force]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> handleList(sender);
            case "tp" -> handleTeleport(sender, args);
            case "remove" -> handleRemove(sender, args);
            default -> handleCreate(sender, args);
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        String worlds = Bukkit.getWorlds().stream()
                .map(World::getName)
                .collect(Collectors.joining(", "));

        sender.sendMessage("§aGeladene Welten:");
        sender.sendMessage("§7" + worlds);
    }

    private void handleTeleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cNur Spieler können teleportiert werden.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /myceliaworld tp <world>");
            return;
        }

        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage("§cWelt nicht geladen oder existiert nicht.");
            return;
        }

        player.teleport(world.getSpawnLocation());
        sender.sendMessage("§aTeleportiert nach §e" + world.getName());
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /myceliaworld remove <world> [--force]");
            return;
        }

        String worldName = args[1];
        boolean force = args.length >= 3 && args[2].equalsIgnoreCase("--force");

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cWelt ist nicht geladen.");
            return;
        }

        if (!force && world.getPlayers().size() > 0) {
            sender.sendMessage("§cSpieler sind noch in der Welt. Nutze --force.");
            return;
        }

        world.getPlayers().forEach(p -> p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()));

        boolean unloaded = Bukkit.unloadWorld(world, false);
        if (!unloaded) {
            sender.sendMessage("§cWelt konnte nicht entladen werden.");
            return;
        }

        File folder = world.getWorldFolder();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            deleteDirectory(folder);
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§aWelt §e" + worldName + " §awurde gelöscht."));
        });
    }

    private void handleCreate(CommandSender sender, String[] args) {
        String worldName = args[0];
        Optional<Long> seed = parseSeed(args);
        if (seed.isPresent()) {
            createWorldSync(sender, worldName, seed.get(), true);
            return;
        }

        sender.sendMessage("§7Hole Seed asynchron vom Mycelia-Treiber...");
        driver.resolveSeedAsync(Optional.empty())
                .whenComplete((resolvedSeed, error) -> {
                    long finalSeed = resolvedSeed;
                    if (error != null) {
                        plugin.getLogger().warning("Asynchroner Seed-Aufruf fehlgeschlagen: " + error.getMessage());
                        finalSeed = driver.nextSecureSeed();
                    }
                    long seedForWorld = finalSeed;
                    BukkitScheduler scheduler = plugin.getServer().getScheduler();
                    scheduler.runTask(plugin, () -> createWorldSync(sender, worldName, seedForWorld, error != null));
                });
    }

    private void createWorldSync(CommandSender sender, String worldName, long resolvedSeed, boolean usedFallback) {
        FileConfiguration config = plugin.getConfig();
        MyceliaChunkGenerator generator = MyceliaChunkGenerator.fromConfig(config, resolvedSeed);

        WorldCreator creator = new WorldCreator(worldName);
        creator.generator(generator);
        creator.seed(resolvedSeed);

        World world = Bukkit.createWorld(creator);
        if (world != null) {
            if (usedFallback) {
                sender.sendMessage("§eTreiber lieferte keinen Seed, nutze sicheren Fallback-Seed.");
            }
            sender.sendMessage("§aNeue Mycelia-Welt erzeugt: " + world.getName() + " (Seed: " + resolvedSeed + ")");
            if (sender instanceof Player player && player.isOnline()) {
                player.teleport(world.getSpawnLocation());
            }
        } else {
            sender.sendMessage("§cWelt konnte nicht erzeugt werden. Siehe Server-Logs für Details.");
        }
    }

    private Optional<Long> parseSeed(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--seed".equalsIgnoreCase(args[i]) || "-s".equalsIgnoreCase(args[i])) {
                try {
                    return Optional.of(Long.parseLong(args[i + 1]));
                } catch (NumberFormatException ignore) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private void deleteDirectory(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteDirectory(f);
                }
            }
        }
        file.delete();
    }
}
