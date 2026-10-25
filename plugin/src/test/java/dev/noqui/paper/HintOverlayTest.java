package dev.noqui.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.*;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.*;

class HintOverlayTest {
    final Plugin plugin = mock(Plugin.class);
    final Server server = mock(Server.class);
    final PluginManager manager = mock(PluginManager.class);
    final Player player = mock(Player.class);
    HintOverlay overlay;
    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(server); when(server.getPluginManager()).thenReturn(manager);
        when(plugin.getResource("hint-pack.properties")).thenAnswer(c -> getClass().getResourceAsStream("/hint-pack.properties"));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); overlay = new HintOverlay(plugin);
    }
    @AfterEach void cleanup() { try(var ignored = mockStatic(HandlerList.class)) { overlay.close(); } }
    static QuizHud.Frame frame(String phase, String hint) { return new QuizHud.Frame(1,2,phase,1000,40000,"",""+hint,"",List.of(),List.of(),0,1); }
    void status(UUID id, PlayerResourcePackStatusEvent.Status state) { overlay.status(new PlayerResourcePackStatusEvent(player,id,state)); }

    @Test void packIsOfferedOnceAndFallbackDoesNotFloodChat() {
        for(int i=0;i<20;i++)overlay.render(player,frame("PLAYING","ㄱㄴ"));
        verify(player,times(1)).addResourcePack(eq(HintOverlay.PACK_ID),startsWith("https://noqui.danta.qzz.io/packs/"),any(byte[].class),eq("노래퀴즈 힌트"),eq(false));
        verify(player,times(1)).sendMessage(any(Component.class)); verify(player,never()).showBossBar(any());
        overlay.render(player,frame("PLAYING","가ㄴ")); verify(player,times(2)).sendMessage(any(Component.class));
        verify(player,never()).sendActionBar(any(Component.class));
    }
    @Test void onlyOurSuccessfullyLoadedPackEnablesTheOverlay() {
        overlay.render(player,frame("PLAYING","ㄱㄴ"));
        status(UUID.randomUUID(),PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        overlay.render(player,frame("PLAYING","ㄱㄴ")); verify(player,never()).showBossBar(any());
        status(HintOverlay.PACK_ID,PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        overlay.render(player,frame("PLAYING","ㄱㄴ"));
        var bar=org.mockito.ArgumentCaptor.forClass(BossBar.class);verify(player).showBossBar(bar.capture());
        assertEquals(0,bar.getValue().progress());assertEquals(BossBar.Color.PINK,bar.getValue().color());
        overlay.render(player,frame("PLAYING","가ㄴ"));verify(player,times(1)).showBossBar(any());
        overlay.render(player,frame("PAUSED","가ㄴ"));verify(player).hideBossBar(bar.getValue());
        verify(player,never()).sendActionBar(any(Component.class));
    }
    @Test void clearsOnlyOwnedBarAndKeepsPackAcrossWorldAndQuizChanges() {
        overlay.render(player,frame("PLAYING","ㄱㄴ"));status(HintOverlay.PACK_ID,PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        overlay.render(player,frame("PLAYING","ㄱㄴ"));var bar=org.mockito.ArgumentCaptor.forClass(BossBar.class);verify(player).showBossBar(bar.capture());
        overlay.clear(player);overlay.clear(player);verify(player,times(1)).hideBossBar(bar.getValue());
        overlay.render(player,frame("PLAYING","ㄱㄴ"));verify(player,times(2)).showBossBar(bar.getValue());
        verify(player,times(1)).addResourcePack(any(UUID.class),anyString(),any(byte[].class),anyString(),anyBoolean());
        overlay.quit(new PlayerQuitEvent(player,Component.empty()));verify(player,times(2)).hideBossBar(bar.getValue());
        overlay.render(player,frame("PLAYING","ㄱㄴ"));verify(player,times(2)).addResourcePack(any(UUID.class),anyString(),any(byte[].class),anyString(),anyBoolean());
    }
    @Test void rejectedOrReloadingPackNeverShowsEncodedText() {
        overlay.render(player,frame("PLAYING","ㄱㄴ"));status(HintOverlay.PACK_ID,PlayerResourcePackStatusEvent.Status.DECLINED);
        overlay.render(player,frame("PLAYING","가ㄴ"));verify(player,never()).showBossBar(any());
        status(HintOverlay.PACK_ID,PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);overlay.render(player,frame("PLAYING","가ㄴ"));
        status(HintOverlay.PACK_ID,PlayerResourcePackStatusEvent.Status.FAILED_RELOAD);verify(player).hideBossBar(any());
        overlay.render(player,frame("PLAYING","가나"));verify(player,times(1)).showBossBar(any());
    }
    @Test void maximumTitleWrapsWithoutTruncationAndNativeAdvanceReturnsToZero() {
        String hint="힣".repeat(160);var lines=HintGlyphs.lines(hint);
        assertTrue(lines.size()<=6);assertEquals("[힌트] "+hint,String.join("",lines));
        assertTrue(lines.stream().allMatch(s->HintGlyphs.width(s)<=HintGlyphs.WIDTH));
        Component text=HintGlyphs.compose(hint);assertEquals(0,advance(text));
    }
    private int advance(Component value) {
        int total=0;
        if(value instanceof net.kyori.adventure.text.TextComponent text) for(int code:text.content().codePoints().toArray())
            total+=code>=0xEE98&&code<=0xF168?code-0xF000:HintGlyphs.width(new String(Character.toChars(code)));
        for(Component child:value.children())total+=advance(child);return total;
    }
}
