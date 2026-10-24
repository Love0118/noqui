package dev.noqui.lobby;

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;

/** Defaults value one answer point at ten stars. Prices may be overridden under lobby.prices.<id>.<starlight|points>. */
public final class LobbyCatalog {
    public enum Currency { STARLIGHT, POINTS }
    public enum Kind { CREDIT, COSMETIC, GADGET }
    public record Product(String id, String label, Kind kind, String slot, long stars, long points) {
        public Product {
            if (!id.matches("[a-z][a-z0-9_]{0,63}") || stars < 1 || points == 0 || points < -1) throw new IllegalArgumentException("Invalid shop product");
            if (kind == Kind.CREDIT && points != -1) throw new IllegalArgumentException("Tickets accept starlight only");
        }
        public long price(Currency currency) {
            long price = currency == Currency.STARLIGHT ? stars : points;
            if (price < 1) throw new IllegalArgumentException("별빛 전용");
            return price;
        }
    }
    private static final List<Product> DEFAULTS=loadDefaults();
    private LobbyCatalog() {}
    public static List<Product> defaults() {return DEFAULTS;}
    private static List<Product> loadDefaults() {
        var products=new ArrayList<Product>();products.add(new Product("question_credit", "출제권", Kind.CREDIT, "", 10, -1));
        try(var stream=LobbyCatalog.class.getResourceAsStream("/lobby-cosmetics.json")){
            if(stream==null)throw new IOException("Missing lobby-cosmetics.json");
            products.addAll(cosmetics(new InputStreamReader(stream,StandardCharsets.UTF_8)));
        }catch(IOException error){throw new IllegalStateException("Could not load cosmetic catalogue",error);}
        products.addAll(List.of(
                gadget("triple_boots", "3단 점프", "boots", 200, 20), gadget("quad_boots", "4단 점프", "boots", 400, 40),
                gadget("hammer", "망치", "hand", 100, 10), gadget("snow_bomb", "눈폭탄", "hand", 100, 10),
                gadget("gravity_suit", "반중력", "suit", 400, 40)));
        return List.copyOf(products);
    }
    static List<Product> cosmetics(Reader reader) {
        try{
            var root=JsonParser.parseReader(reader).getAsJsonObject();
            if(!root.keySet().equals(Set.of("schemaVersion","cosmetics"))||!root.get("schemaVersion").getAsJsonPrimitive().isNumber()
                    ||root.get("schemaVersion").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE)!=0)throw new IllegalArgumentException();
            var rows=root.getAsJsonArray("cosmetics");if(rows.size()<6||rows.size()>100)throw new IllegalArgumentException();
            var reserved=Set.of("question_credit","triple_boots","quad_boots","hammer","snow_bomb","gravity_suit");
            var products=new LinkedHashMap<String,Product>();
            for(var row:rows){var data=row.getAsJsonObject();if(!data.keySet().equals(Set.of("id","label","slot")))throw new IllegalArgumentException();
                for(String key:data.keySet())if(!data.get(key).isJsonPrimitive()||!data.getAsJsonPrimitive(key).isString())throw new IllegalArgumentException();
                String id=data.get("id").getAsString(),label=data.get("label").getAsString(),slot=data.get("slot").getAsString();
                if(reserved.contains(id)||label.isBlank()||!label.equals(label.trim())||label.codePointCount(0,label.length())>80||label.codePoints().anyMatch(Character::isISOControl)||!Set.of("head","face","back").contains(slot))throw new IllegalArgumentException();
                if(products.putIfAbsent(id,cosmetic(id,label,slot))!=null)throw new IllegalArgumentException();
            }
            var legacy=Map.of("pirate_hat","head","backpack","back","glasses","face","crown","head","cap","head","bunny","head");
            for(var entry:legacy.entrySet())if(!products.containsKey(entry.getKey())||!products.get(entry.getKey()).slot().equals(entry.getValue()))throw new IllegalArgumentException();
            return List.copyOf(products.values());
        }catch(RuntimeException error){throw new IllegalArgumentException("Invalid cosmetic catalogue schema, product or legacy identity",error);}
    }
    private static Product cosmetic(String id, String label, String slot) { return new Product(id,label,Kind.COSMETIC,slot,200,20); }
    private static Product gadget(String id,String label,String slot,long stars,long points) { return new Product(id,label,Kind.GADGET,slot,stars,points); }
}
