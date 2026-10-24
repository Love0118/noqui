package dev.noqui.api;

import org.bukkit.entity.Player;

/** Optional defense adapter, registered only while the current Moma generation is active. */
public interface DefenseService {
    boolean active(Player player);
    void startGame(Player player);
    void releaseLobbyTools(Player player);
    void silenceBgm(Player player);
    void sendLobby(Player player);
    void openMenu(Player player);
    default void openSettings(Player player){throw new IllegalStateException("설정 메뉴를 사용할 수 없습니다. 디펜스 플러그인을 업데이트하세요.");}
    default void openTraits(Player player){throw new IllegalStateException("특성 메뉴를 사용할 수 없습니다. 디펜스 플러그인을 업데이트하세요.");}
    /** Use the normal leave checks and cleanup before deliberate quiz-lobby travel. */
    default void leaveGame(Player player){throw new IllegalStateException("디펜스 이동 기능을 업데이트하세요.");}
}
