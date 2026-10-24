package dev.noqui.paper;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.*;

final class Cabin {
    final World world;
    final Location spawn;
    private final double centerX,centerZ,half;
    Cabin(FileConfiguration config) {
        String name=config.getString("world","noqui_cabin");
        if(!name.matches("[a-z0-9_-]{1,32}") || Set.of("world","mud_lobby","moma_arenas").contains(name))throw new IllegalArgumentException("Invalid dedicated Noqui world name");
        // Paper owns migration from imported worlds to its current dimension layout.
        // Validate the actual loaded map below instead of assuming a legacy level.dat path.
        world=Objects.requireNonNull(Bukkit.createWorld(new WorldCreator(name).generator(new VoidGenerator())));
        centerX=config.getDouble("border.center-x",416);centerZ=config.getDouble("border.center-z",-192);double size=config.getDouble("border.size",250);half=size/2;
        if(!Double.isFinite(centerX)||!Double.isFinite(centerZ)||size!=250)throw new IllegalArgumentException("Noqui border must be 250 blocks");
        spawn=new Location(world,config.getDouble("spawn.x",411.5),config.getDouble("spawn.y",11),config.getDouble("spawn.z",-205.5),
                (float)config.getDouble("spawn.yaw",.3003012),(float)config.getDouble("spawn.pitch",0));
        spawn.checkFinite();
        if(outside(spawn) || !world.getBlockAt(spawn.clone().subtract(0,1,0)).getType().isSolid()
                || !world.getBlockAt(spawn).isPassable() || !world.getBlockAt(spawn.clone().add(0,1,0)).isPassable())throw new IllegalStateException("Noqui spawn needs a solid floor and two clear blocks");
        world.getWorldBorder().setCenter(centerX,centerZ);world.getWorldBorder().setSize(size);world.setSpawnLocation(spawn);
        world.setGameRule(GameRules.SPAWN_MOBS,false);world.setGameRule(GameRules.ADVANCE_TIME,false);world.setGameRule(GameRules.ADVANCE_WEATHER,false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED,0);world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,0);
        world.setGameRule(GameRules.PVP,true);world.setGameRule(GameRules.KEEP_INVENTORY,true);
        world.setTime(6000);world.setStorm(false);world.setThundering(false);
    }
    boolean contains(Location location) {return location!=null && world.equals(location.getWorld());}
    boolean outside(Location location) {return location.getY()<world.getMinHeight()+1 || location.getX()<centerX-half || location.getX()>=centerX+half || location.getZ()<centerZ-half || location.getZ()>=centerZ+half;}
    static final class VoidGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise(){return false;}
        @Override public boolean shouldGenerateSurface(){return false;}
        @Override public boolean shouldGenerateBedrock(){return false;}
        @Override public boolean shouldGenerateCaves(){return false;}
        @Override public boolean shouldGenerateDecorations(){return false;}
        @Override public boolean shouldGenerateMobs(){return false;}
        @Override public boolean shouldGenerateStructures(){return false;}
    }
}
