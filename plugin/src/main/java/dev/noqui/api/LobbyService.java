package dev.noqui.api;

import java.util.UUID;
import org.bukkit.entity.Player;

public interface LobbyService {
    long starlight(UUID player);
    long answerPoints(UUID player);
    void rewardStarlight(UUID player, String transactionId, long amount);
    void rewardAnswerPoints(UUID player, String transactionId, long amount);
    void openShop(Player player);
    default void openMiningShop(Player player,Runnable openSales,Runnable openUpgrade){openShop(player);}
    void showMoney(Player player);
    /** Idempotent durable debit; false means insufficient funds, with no changes applied. */
    default boolean spendStarlight(UUID player, String transactionId, long amount) { throw new IllegalStateException("별빛 지갑을 업데이트하세요."); }
    default void openWardrobe(Player player) {}
    /** Release temporary effects before the cabin inventory owner captures the complete cabin inventory. Leave physical stacks in place. */
    default void prepareInventoryChange(Player player) {}
    /** Reconcile serial ownership and pending delivery after the cabin inventory owner restores an inventory or a handoff fails. */
    default void inventoryChanged(Player player) {}
}
