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

import java.util.Optional;

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
            sender.sendMessage("§cBitte gebe einen Weltnamen an. Beispiel: /" + label + " myceliawelt [--seed <zahl>]");
            return true;
        }

        String worldName = args[0];
        Optional<Long> seed = parseSeed(args);
        long resolvedSeed = driver.resolveSeed(seed);

        FileConfiguration config = plugin.getConfig();
        MyceliaChunkGenerator generator = MyceliaChunkGenerator.fromConfig(config, resolvedSeed);

        WorldCreator creator = new WorldCreator(worldName);
        creator.generator(generator);
        creator.seed(resolvedSeed);

        World world = Bukkit.createWorld(creator);
        if (world != null) {
            sender.sendMessage("§aNeue Mycelia-Welt erzeugt: " + world.getName() + " (Seed: " + resolvedSeed + ")");
            if (sender instanceof Player player) {
                player.teleport(world.getSpawnLocation());
            }
        } else {
            sender.sendMessage("§cWelt konnte nicht erzeugt werden. Siehe Server-Logs für Details.");
        }
        return true;
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
}
