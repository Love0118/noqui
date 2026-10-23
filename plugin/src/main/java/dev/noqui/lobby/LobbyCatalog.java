package dev.noqui.lobby;

import java.util.*;

/** Defaults value one answer point at ten stars. Prices may be overridden under lobby.prices.<id>.<starlight|points>. */
public final class LobbyCatalog {
    public enum Currency { STARLIGHT, POINTS }
    public enum Kind { CREDIT, COSMETIC, GADGET }
    public record Product(String id, String label, Kind kind, String slot, long stars, long points) {
        public Product {
            if (!id.matches("[a-z_]+") || stars < 1 || points == 0 || points < -1) throw new IllegalArgumentException("Invalid shop product");
            if (kind == Kind.CREDIT && points != -1) throw new IllegalArgumentException("Tickets accept starlight only");
        }
        public long price(Currency currency) {
            long price = currency == Currency.STARLIGHT ? stars : points;
            if (price < 1) throw new IllegalArgumentException("별빛 전용");
            return price;
        }
    }
    private LobbyCatalog() {}
    public static List<Product> defaults() {
        return List.of(new Product("question_credit", "출제권", Kind.CREDIT, "", 10, -1),
                cosmetic("pirate_hat", "해적 모자", "head"), cosmetic("backpack", "가방", "back"),
                cosmetic("glasses", "안경", "face"), cosmetic("crown", "별 왕관", "head"),
                cosmetic("cap", "별 모자", "head"), cosmetic("bunny", "토끼 귀", "head"),
                gadget("triple_boots", "3단 점프", "boots", 20, 2), gadget("quad_boots", "4단 점프", "boots", 40, 4),
                gadget("hammer", "망치", "hand", 10, 1), gadget("snow_bomb", "눈폭탄", "hand", 10, 1),
                gadget("gravity_suit", "반중력", "suit", 40, 4));
    }
    private static Product cosmetic(String id, String label, String slot) { return new Product(id,label,Kind.COSMETIC,slot,20,2); }
    private static Product gadget(String id,String label,String slot,long stars,long points) { return new Product(id,label,Kind.GADGET,slot,stars,points); }
}
