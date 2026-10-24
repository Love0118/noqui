package dev.noqui.api;

import org.bukkit.entity.Player;

/** Main-thread bridge to the active mining runtime. Resolve it on each use. */
public interface MiningService {
    boolean ownsInventory(Player player);
    void openOreSales(Player player);
    void refreshCargo(Player player);
}
