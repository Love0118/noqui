package dev.noqui.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.*;

/** Persistent transaction marker survives disconnect, shutdown and plugin replacement. */
public final class PlayerRecovery {
    private static final NamespacedKey INVENTORY=new NamespacedKey("noqui","original_inventory"),STATE=new NamespacedKey("noqui","original_state"),
            PRESENT=new NamespacedKey("noqui","bgm_original_present"),VALUE=new NamespacedKey("noqui","bgm_original_value"),
            MEMBERSHIP=new NamespacedKey("noqui","membership"),BGM=new NamespacedKey("momadefense","bgm_muted");
    public boolean pending(Player player) {return player.getPersistentDataContainer().has(STATE,PersistentDataType.STRING);}
    public UUID enter(Player player) {
        if(pending(player))throw new IllegalStateException("Previous Noqui entry must be recovered first");
        var p=player.getPersistentDataContainer();var l=player.getLocation();
        byte[] inventory=ItemStack.serializeItemsAsBytes(player.getInventory().getContents());
        String state=String.join(";",l.getWorld().getUID().toString(),Double.toString(l.getX()),Double.toString(l.getY()),Double.toString(l.getZ()),
                Float.toString(l.getYaw()),Float.toString(l.getPitch()),player.getGameMode().name(),Boolean.toString(player.getAllowFlight()),
                Boolean.toString(player.isFlying()),Integer.toString(player.getInventory().getHeldItemSlot()));
        Byte original=p.get(BGM,PersistentDataType.BYTE);UUID membership=UUID.randomUUID();
        p.set(INVENTORY,PersistentDataType.BYTE_ARRAY,inventory);p.set(STATE,PersistentDataType.STRING,state);
        p.set(PRESENT,PersistentDataType.BYTE,(byte)(original==null?0:1));
        if(original!=null)p.set(VALUE,PersistentDataType.BYTE,original);else p.remove(VALUE);
        p.set(MEMBERSHIP,PersistentDataType.STRING,membership.toString());
        // Persist the recovery marker before changing the preference or inventory.
        player.saveData();p.set(BGM,PersistentDataType.BYTE,(byte)1);player.saveData();return membership;
    }
    public void toggleBgm(Player player) {
        if(!pending(player))return;
        var p=player.getPersistentDataContainer();boolean muted=p.getOrDefault(BGM,PersistentDataType.BYTE,(byte)0)!=0;
        p.set(BGM,PersistentDataType.BYTE,(byte)(muted?0:1));
        player.sendActionBar(net.kyori.adventure.text.Component.text(muted?"BGM ON":"BGM OFF"));
    }
    public boolean restore(Player player,boolean teleport) {
        var p=player.getPersistentDataContainer();String encoded=p.get(STATE,PersistentDataType.STRING);
        if(encoded==null)return false;
        String[] s=encoded.split(";",-1);if(s.length!=10)throw new IllegalStateException("Invalid Noqui recovery state");
        byte[] inventory=p.get(INVENTORY,PersistentDataType.BYTE_ARRAY);
        ItemStack[] original=inventory==null?null:ItemStack.deserializeItemsFromBytes(inventory);
        GameMode mode=GameMode.valueOf(s[6]);
        World world=Bukkit.getWorld(UUID.fromString(s[0]));
        if(p.getOrDefault(PRESENT,PersistentDataType.BYTE,(byte)0)==0)p.remove(BGM);
        else p.set(BGM,PersistentDataType.BYTE,p.getOrDefault(VALUE,PersistentDataType.BYTE,(byte)0));
        if(original!=null)player.getInventory().setContents(original);
        player.getInventory().setHeldItemSlot(Math.clamp(Integer.parseInt(s[9]),0,8));
        player.setGameMode(mode);player.setAllowFlight(Boolean.parseBoolean(s[7]));
        player.setFlying(Boolean.parseBoolean(s[7])&&Boolean.parseBoolean(s[8]));
        if(teleport && world!=null)player.teleport(new Location(world,Double.parseDouble(s[1]),Double.parseDouble(s[2]),Double.parseDouble(s[3]),Float.parseFloat(s[4]),Float.parseFloat(s[5])));
        for(var key:List.of(INVENTORY,STATE,PRESENT,VALUE,MEMBERSHIP))p.remove(key);
        player.saveData();return true;
    }
}
