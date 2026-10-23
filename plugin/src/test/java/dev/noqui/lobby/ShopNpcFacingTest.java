package dev.noqui.lobby;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import com.destroystokyo.paper.SkinParts;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class ShopNpcFacingTest {
    World world;
    Mannequin mannequin;
    ShopNpc shop;
    MockedStatic<ResolvableProfile> profiles;
    MockedStatic<SkinParts> skinFactory;
    SkinParts.Mutable allParts;
    Location current;

    @BeforeEach void setup() {
        world=mock(World.class);WorldBorder border=mock(WorldBorder.class);Chunk chunk=mock(Chunk.class);
        Block air=mock(Block.class),floor=mock(Block.class);Material solid=mock(Material.class);when(solid.isSolid()).thenReturn(true);
        when(floor.getType()).thenReturn(solid);when(air.isPassable()).thenReturn(true);when(world.getWorldBorder()).thenReturn(border);when(border.isInside(any())).thenReturn(true);
        when(world.getBlockAt(any(Location.class))).thenAnswer(c->((Location)c.getArgument(0)).getY()<64?floor:air);when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
        mannequin=mock(Mannequin.class);current=new Location(world,0,64,0,37,12);
        when(mannequin.getUniqueId()).thenReturn(new UUID(0,1));when(mannequin.isValid()).thenReturn(true);when(mannequin.getScoreboardTags()).thenReturn(Set.of(ShopNpc.TAG));
        when(mannequin.getLocation()).thenAnswer(c->current.clone());when(mannequin.getEyeLocation()).thenAnswer(c->current.clone().add(0,1.62,0));
        when(world.getEntitiesByClass(Mannequin.class)).thenReturn(List.of(mannequin));
        profiles=mockStatic(ResolvableProfile.class);var builder=mock(ResolvableProfile.Builder.class,RETURNS_SELF);profiles.when(ResolvableProfile::resolvableProfile).thenReturn(builder);when(builder.build()).thenReturn(mock(ResolvableProfile.class));
        skinFactory=mockStatic(SkinParts.class);allParts=mock(SkinParts.Mutable.class);skinFactory.when(SkinParts::allParts).thenReturn(allParts);
        shop=new ShopNpc(world,0,64,0);shop.ensure();clearInvocations(mannequin);
    }
    @AfterEach void close(){profiles.close();skinFactory.close();}
    Player player(double x,double y,double z,long id) {
        Player p=mock(Player.class);when(p.getUniqueId()).thenReturn(new UUID(0,id));when(p.isOnline()).thenReturn(true);when(p.getGameMode()).thenReturn(GameMode.ADVENTURE);when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(new Location(world,x,y,z));when(p.getEyeLocation()).thenReturn(new Location(world,x,y+1.62,z));return p;
    }
    @Test void exactSevenBlocksIsIncludedAndOutsideTheRadiusKeepsLastFacing() {
        Player edge=player(7,64,0,2);when(world.getPlayers()).thenReturn(List.of(edge));shop.faceNearest();verify(mannequin).setRotation(eq(-90f),eq(0f));
        clearInvocations(mannequin);Player outside=player(7.0001,64,0,3);when(world.getPlayers()).thenReturn(List.of(outside));shop.faceNearest();verify(mannequin,never()).setRotation(anyFloat(),anyFloat());verify(mannequin,never()).teleport(any(Location.class));
    }
    @Test void nearestValidSameWorldPlayerWinsAndPitchTracksEyeHeight() {
        Player near=player(0,65,2,2),far=player(-5,64,0,3),spectator=player(0,64,.1,4),dead=player(0,64,.2,5),other=player(0,64,.3,6);
        when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);when(dead.isDead()).thenReturn(true);when(other.getWorld()).thenReturn(mock(World.class));
        when(world.getPlayers()).thenReturn(List.of(far,other,spectator,dead,near));shop.faceNearest();
        verify(mannequin).setRotation(eq(0f),floatThat(p->Math.abs(p+26.565f)<.01));verify(mannequin,never()).teleport(any(Location.class));
    }
    @Test void equidistantTargetsUseStableUuidTieBreakAndOfflinePlayersAreIgnored() {
        Player lower=player(-2,64,0,2),higher=player(2,64,0,3),offline=player(0,64,.1,4);when(offline.isOnline()).thenReturn(false);
        when(world.getPlayers()).thenReturn(List.of(higher,offline,lower));shop.faceNearest();verify(mannequin).setRotation(90f,0f);
    }
    @Test void persistentSetupUsesExactNameWithoutResettingExistingPositionOrRotation() {
        // A fresh controller finds the already-saved entity; setup does not teleport it at the same point.
        ShopNpc reloaded=new ShopNpc(world,0,64,0);reloaded.ensure();reloaded.ensure();
        verify(mannequin,times(1)).customName(Component.text("상점"));verify(mannequin).setImmovable(true);verify(mannequin).setInvulnerable(true);
        verify(mannequin,times(1)).setSkinParts(allParts);
        verify(mannequin,never()).setRotation(anyFloat(),anyFloat());verify(mannequin,never()).teleport(any(Location.class));
        when(world.getPlayers()).thenReturn(List.of());reloaded.faceNearest();verify(mannequin,never()).setRotation(anyFloat(),anyFloat());
    }
    @Test void restoringTheConfiguredNpcPositionPreservesSavedFacing() {
        current=new Location(world,1,64,0,37,12);new ShopNpc(world,0,64,0).ensure();
        verify(mannequin).teleport(argThat((Location at)->at.getX()==0&&at.getY()==64&&at.getYaw()==37&&at.getPitch()==12));
    }
}
