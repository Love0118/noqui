package dev.noqui.api;

import java.util.UUID;
import org.bukkit.entity.Player;

public interface LobbyService {
    long starlight(UUID player);
    long answerPoints(UUID player);
    void rewardStarlight(UUID player, String transactionId, long amount);
    void rewardAnswerPoints(UUID player, String transactionId, long amount);
    void openShop(Player player);
    void showMoney(Player player);
}
