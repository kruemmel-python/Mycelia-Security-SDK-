package com.mycelia.mc;

import com.mycelia.mc.command.MyceliaWorldCommand;
import com.mycelia.mc.driver.MyceliaDriver;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

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
    }
}
