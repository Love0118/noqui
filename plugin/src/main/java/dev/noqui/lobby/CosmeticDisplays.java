package dev.noqui.lobby;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;

/** Visual-only entities. No armor slots, inventory slots, passengers or defense tools are changed. */
final class CosmeticDisplays {
    static final String TAG="noqui_lobby_cosmetic_v1";
    private record Visual(String slot,String model,ItemDisplay display) {}
    private record Outfit(World world,Map<String,String> items,List<Visual> visuals) {}
    private final Plugin plugin;
    private final World cabin;
    private final Map<UUID,Outfit> outfits=new HashMap<>();
    CosmeticDisplays(Plugin plugin,World cabin){this.plugin=plugin;this.cabin=cabin;}
    void purge(){for(World world:Bukkit.getWorlds())for(ItemDisplay display:world.getEntitiesByClass(ItemDisplay.class))if(display.getScoreboardTags().contains(TAG))display.remove();outfits.clear();}
    void tick(Player p,Map<String,String> equipment) {
        var visible=new TreeMap<String,String>();
        if(!p.isDead()&&!p.isInvisible()&&p.getGameMode()!=GameMode.SPECTATOR)equipment.forEach((slot,model)->{
            if(Set.of("head","face","back").contains(slot)||p.getWorld().equals(cabin))visible.put(slot,model);
        });
        Outfit outfit=outfits.get(p.getUniqueId());
        if(outfit!=null&&(outfit.world!=p.getWorld()||!outfit.items.equals(visible)||outfit.visuals.stream().anyMatch(v->!v.display.isValid()))){leave(p.getUniqueId());outfit=null;}
        if(outfit==null&&!visible.isEmpty()) {
            var visuals=new ArrayList<Visual>();
            visible.forEach((slot,model)->{
                ItemDisplay display=p.getWorld().spawn(at(p,slot),ItemDisplay.class,d->{d.addScoreboardTag(TAG);d.setPersistent(false);d.setInvulnerable(true);d.setGravity(false);});
                ItemStack item=new ItemStack(Material.PAPER);var meta=item.getItemMeta();meta.setItemModel(new NamespacedKey("noqui",model));item.setItemMeta(meta);
                display.setItemStack(item);display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);display.setTeleportDuration(5);
                display.setTransformationMatrix(new Matrix4f().scale(slot.equals("back")?.8f:1f));
                visuals.add(new Visual(slot,model,display));
            });
            outfit=new Outfit(p.getWorld(),Map.copyOf(visible),visuals);outfits.put(p.getUniqueId(),outfit);
        }
        if(outfit!=null)for(Visual visual:outfit.visuals){Location target=at(p,visual.slot);if(visual.display.getLocation().distanceSquared(target)>.0004||Math.abs(visual.display.getYaw()-target.getYaw())>1)visual.display.teleport(target);}
    }
    private static Location at(Player p,String slot) {
        Location at=p.getLocation().clone();double y=switch(slot){case "head"->1.85;case "face"->1.62;case "back","suit"->1.1;case "boots"->.12;default->1.05;};at.add(0,y,0);
        org.bukkit.util.Vector forward=at.getDirection().setY(0);if(forward.lengthSquared()>0)forward.normalize();
        if(slot.equals("back"))at.subtract(forward.multiply(.28));else if(slot.equals("face"))at.add(forward.multiply(.28));else if(slot.equals("hand"))at.add(forward.multiply(.4));
        at.setPitch(0);return at;
    }
    void leave(UUID id){Outfit outfit=outfits.remove(id);if(outfit!=null)for(Visual visual:outfit.visuals)visual.display.remove();}
    void close(){for(UUID id:List.copyOf(outfits.keySet()))leave(id);}
}
