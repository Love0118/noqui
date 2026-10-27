package dev.noqui.paper;

import java.util.List;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CabinBoundaryTest {
    private final World world=mock(World.class),other=mock(World.class);
    private Cabin cabin;
    @BeforeEach void setup()throws Exception{
        cabin=mock(Cabin.class,CALLS_REAL_METHODS);when(world.getMinHeight()).thenReturn(-64);
        for(var entry:java.util.Map.<String,Object>of("world",world,"spawn",new Location(world,411.5,11,-205.5),"centerX",416d,"centerZ",-192d,"half",125d).entrySet()){
            var field=Cabin.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(cabin,entry.getValue());
        }
    }
    @Test void logicalMapBoundsRemainExactly250Blocks(){
        assertFalse(cabin.outside(cabin.spawn));
        assertFalse(cabin.outside(new Location(world,291,11,-317)));
        assertFalse(cabin.outside(new Location(world,540.999,11,-67.001)));
        for(var to:List.of(new Location(world,290.999,11,-192),new Location(world,541,11,-192),
                new Location(world,416,11,-317.001),new Location(world,416,11,-67),new Location(world,416,-64,-192)))assertTrue(cabin.outside(to));
    }
    @Test void escapingAnyEdgeOrFallingBelowTheMapReturnsToSpawnWithoutAWorldBorder(){
        var plugin=mock(NoquiPlugin.class);plugin.cabin=cabin;var listener=new NoquiListener(plugin);var player=mock(Player.class);
        for(var to:List.of(new Location(world,290.999,11,-192),new Location(world,541,11,-192),
                new Location(world,416,11,-317.001),new Location(world,416,11,-67),new Location(world,416,-64,-192))){
            var event=new PlayerMoveEvent(player,cabin.spawn.clone(),to);listener.move(event);
            assertEquals(cabin.spawn,event.getTo());assertNotSame(cabin.spawn,event.getTo());
        }
        verify(player,times(5)).setFallDistance(0);
    }
    @Test void ordinaryMovementAndTravelToOtherWorldsAreUntouched(){
        var plugin=mock(NoquiPlugin.class);plugin.cabin=cabin;var listener=new NoquiListener(plugin);var player=mock(Player.class);
        for(var to:List.of(new Location(world,416,11,-192),new Location(other,2000,64,2000))){
            var event=new PlayerMoveEvent(player,cabin.spawn.clone(),to);listener.move(event);assertSame(to,event.getTo());
        }
        verify(player,never()).setFallDistance(anyFloat());
    }
}
