package dev.noqui.lobby;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyCatalogTest {
    @Test void everyNonTicketPriceIsTenTimesThePriorPriceWithoutChangingCurrencyRatio(){
        var prior=Map.ofEntries(Map.entry("pirate_hat",20L),Map.entry("backpack",20L),Map.entry("glasses",20L),Map.entry("crown",20L),Map.entry("cap",20L),Map.entry("bunny",20L),Map.entry("triple_boots",20L),Map.entry("quad_boots",40L),Map.entry("hammer",10L),Map.entry("snow_bomb",10L),Map.entry("gravity_suit",40L));
        var products=LobbyCatalog.defaults();assertEquals(1,products.stream().filter(p->p.kind()==LobbyCatalog.Kind.CREDIT).count());assertEquals(5,products.stream().filter(p->p.kind()==LobbyCatalog.Kind.GADGET).count());for(var product:products){
            if(product.kind()==LobbyCatalog.Kind.CREDIT){assertEquals("question_credit",product.id());assertEquals(10,product.stars());assertEquals(-1,product.points());}
            else{long old=product.kind()==LobbyCatalog.Kind.COSMETIC?20:prior.get(product.id());assertEquals(old*10,product.stars());assertEquals(old,product.points());assertEquals(product.points()*10,product.stars());}
        }
        assertEquals(products,LobbyCatalog.defaults());
    }
    private com.google.gson.JsonObject baseline(){
        var root=new com.google.gson.JsonObject();root.addProperty("schemaVersion",1);var rows=new com.google.gson.JsonArray();
        for(var product:LobbyCatalog.defaults())if(Set.of("pirate_hat","backpack","glasses","crown","cap","bunny").contains(product.id())){
            var row=new com.google.gson.JsonObject();row.addProperty("id",product.id());row.addProperty("label",product.label());row.addProperty("slot",product.slot());rows.add(row);
        }root.add("cosmetics",rows);return root;
    }
    @Test void catalogueAcceptsEightyDistinctModelsAndRetainsLegacyIds(){
        var root=baseline();for(int i=0;i<74;i++){var row=new com.google.gson.JsonObject();row.addProperty("id","fixture_hat_"+i);row.addProperty("label","치장 "+i);row.addProperty("slot",List.of("head","face","back").get(i%3));root.getAsJsonArray("cosmetics").add(row);}
        var products=LobbyCatalog.cosmetics(new java.io.StringReader(root.toString()));assertEquals(80,products.size());assertEquals(80,products.stream().map(LobbyCatalog.Product::id).distinct().count());assertTrue(products.stream().allMatch(p->p.stars()==200&&p.points()==20));
    }
    @Test void malformedSchemaDuplicatesUnsafeIdsLabelsSlotsAndMissingLegacyEntriesAreRejected(){
        for(String invalid:List.of("schema","duplicate","id","label","slot","missing","reserved")){
            var root=baseline();var first=root.getAsJsonArray("cosmetics").get(0).getAsJsonObject();
            switch(invalid){case "schema"->root.addProperty("schemaVersion",2);case "duplicate"->root.getAsJsonArray("cosmetics").add(first.deepCopy());case "id"->first.addProperty("id","../unsafe");case "label"->first.addProperty("label"," ");case "slot"->first.addProperty("slot","feet");case "reserved"->first.addProperty("id","hammer");default->root.getAsJsonArray("cosmetics").remove(0);}
            assertThrows(IllegalArgumentException.class,()->LobbyCatalog.cosmetics(new java.io.StringReader(root.toString())),invalid);
        }
    }
}
