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
    /** Use the normal leave checks and cleanup before deliberate quiz-lobby travel. */
    default void leaveGame(Player player){throw new IllegalStateException("디펜스 이동 기능을 업데이트하세요.");}
}
