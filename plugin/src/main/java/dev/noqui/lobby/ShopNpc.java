package dev.noqui.lobby;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import com.destroystokyo.paper.SkinParts;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.profile.PlayerTextures;
import java.util.*;

final class ShopNpc {
    public static final String TAG="noqui_lobby_shop_v1";
    private final Location position;
    private Mannequin npc;
    ShopNpc(World world,double x,double y,double z){position=new Location(world,x,y,z,90,0);position.checkFinite();}
    void ensure() {
        if(npc!=null&&npc.isValid())return;
        if(!safe(position))throw new IllegalStateException("Lobby shop needs a solid floor, two air blocks and border access");
        position.getChunk().load();
        List<Mannequin> existing=position.getWorld().getEntitiesByClass(Mannequin.class).stream().filter(e->e.getScoreboardTags().contains(TAG))
                .sorted(Comparator.comparing(Entity::getUniqueId)).toList();
        npc=existing.isEmpty()?position.getWorld().spawn(position,Mannequin.class,m->{m.addScoreboardTag(TAG);m.setPersistent(true);}):existing.getFirst();
        for(int i=1;i<existing.size();i++)existing.get(i).remove();
        npc.addScoreboardTag(TAG);npc.setPersistent(true);npc.setImmovable(true);npc.setGravity(false);npc.setInvulnerable(true);npc.setCollidable(false);npc.setSilent(true);
        npc.setProfile(ResolvableProfile.resolvableProfile().uuid(UUID.fromString("7d5243d5-c30e-4bdd-a039-71ec1ad10002"))
                .skinPatch(p->p.body(Key.key("noqui:entity/shopkeeper")).model(PlayerTextures.SkinModel.SLIM)).build());
        // Persistent mannequins can retain a previous layer mask; the authored skin uses all overlays.
        npc.setSkinParts(SkinParts.allParts());
        // Empty text still draws the mannequin's second label; null sets hide_description.
        npc.customName(Component.text("상점"));npc.setCustomNameVisible(true);npc.setDescription(null);
        // Reusing a persistent NPC must not reset its current facing on every reload.
        Location current=npc.getLocation();
        if(current!=null&&(current.getWorld()!=position.getWorld()||current.distanceSquared(position)>.0001)) {
            Location target=position.clone();target.setYaw(current.getYaw());target.setPitch(current.getPitch());npc.teleport(target);
        }
    }
    void faceNearest() {
        if(npc==null||!npc.isValid())return;
        Player nearest=null;double nearestDistance=49;
        Location from=npc.getLocation();
        if(from==null||from.getWorld()!=position.getWorld())return;
        for(Player player:position.getWorld().getPlayers()) {
            if(!player.isOnline()||player.isDead()||player.getGameMode()==GameMode.SPECTATOR||player.getWorld()!=position.getWorld())continue;
            double distance=player.getLocation().distanceSquared(from);
            if(distance<=nearestDistance&&(nearest==null||distance<nearestDistance||player.getUniqueId().compareTo(nearest.getUniqueId())<0)) {
                nearest=player;nearestDistance=distance;
            }
        }
        // No target keeps the last facing; the mannequin remains stationary.
        if(nearest==null)return;
        Location eyes=npc.getEyeLocation(),target=nearest.getEyeLocation();
        double x=target.getX()-eyes.getX(),y=target.getY()-eyes.getY(),z=target.getZ()-eyes.getZ();
        if(x*x+y*y+z*z<1e-8)return;
        float yaw=(float)Math.toDegrees(Math.atan2(-x,z));
        float pitch=(float)-Math.toDegrees(Math.atan2(y,Math.hypot(x,z)));
        npc.setRotation(yaw==0?0:yaw,pitch==0?0:pitch);
    }
    static boolean owned(Entity entity){return entity.getScoreboardTags().contains(TAG);}
    static boolean safe(Location at){return at.getWorld().getWorldBorder().isInside(at)&&at.getBlock().isPassable()&&at.clone().add(0,1,0).getBlock().isPassable()&&at.clone().subtract(0,1,0).getBlock().getType().isSolid();}
    // Persistent NPC stays in its world over plugin reload; ensure() deduplicates it next start.
}
