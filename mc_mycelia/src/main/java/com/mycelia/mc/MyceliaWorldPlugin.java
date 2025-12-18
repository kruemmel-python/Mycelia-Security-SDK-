package com.mycelia.mc;
import com.mycelia.mc.command.MyceliaWorldCommand;
import com.mycelia.mc.driver.MyceliaDriver;
import com.mycelia.mc.driver.MyceliaWorldData;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

public class MyceliaWorldPlugin extends JavaPlugin {

    private MyceliaDriver myceliaDriver;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.myceliaDriver = new MyceliaDriver(this, getConfig());
        PluginCommand command = getCommand("myceliaworld");
        if (command != null) {
            command.setExecutor(new MyceliaWorldCommand(this, myceliaDriver));
            getLogger().info("mc_mycelia bereit. Nutze /myceliaworld für neue Welten.");
        } else {
            getLogger().severe("mc_mycelia konnte den Command 'myceliaworld' nicht registrieren.");
        }

        // Optionales Warmup: lädt den Treiber einmal asynchron, damit Kernel-Vorbereitung/IO
        // nicht den ersten Command blockiert oder in den Timeout läuft.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            getLogger().info("[mc_mycelia] Warmup: Treiber wird vorgeladen...");
            myceliaDriver.resolveWorldDataWarmupAsync(Optional.empty()).join();
            getLogger().info("[mc_mycelia] Warmup: abgeschlossen.");
        });
    }

    public void saveWorldMeta(String worldName, MyceliaWorldData data) {
        File file = new File(getDataFolder(), "worlds.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        String path = "worlds." + worldName;
        config.set(path + ".seed", data.seed());
        config.set(path + ".baseBlock", data.baseBlock());
        config.set(path + ".surfaceBlock", data.surfaceBlock());
        config.set(path + ".oreBlock", data.oreBlock());
        config.set(path + ".scale", data.scale());
        config.set(path + ".seaLevel", data.seaLevel());

        try {
            config.save(file);
        } catch (IOException e) {
            getLogger().severe("Konnte Welt-Metadaten für " + worldName + " nicht speichern!");
        }
    }

    public ConfigurationSection getWorldMeta(String worldName) {
        File file = new File(getDataFolder(), "worlds.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        return config.getConfigurationSection("worlds." + worldName);
    }
}
