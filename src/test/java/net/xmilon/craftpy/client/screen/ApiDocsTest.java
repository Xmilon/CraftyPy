package net.xmilon.craftpy.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The completion popup depends on these docs for its description panel. */
class ApiDocsTest {

    @Test
    void apiNamesCarrySignatureAndDescription() {
        for (String name : new String[] { "block", "item", "tex", "snd", "mdl", "recipe_shaped", "loot_self", "tag" }) {
            ApiDocs.Doc doc = ApiDocs.get(name);
            assertNotNull(doc, "missing docs for " + name);
            assertFalse(doc.signature().isBlank(), name + " signature");
            assertFalse(doc.description().isBlank(), name + " description");
        }
    }

    @Test
    void blockFieldsMatchTheDeclarationSurface() {
        assertEquals(8, ApiDocs.fieldsFor("block").size());
        for (ApiDocs.Field field : ApiDocs.fieldsFor("block")) {
            assertFalse(field.description().isBlank(), field.name() + " needs a description");
        }
    }

    @Test
    void itemFieldsCoverStackFoodAndEffects() {
        assertTrue(ApiDocs.fieldsFor("item").stream().anyMatch(f -> f.name().equals("max_stack")));
        assertTrue(ApiDocs.fieldsFor("item").stream().anyMatch(f -> f.name().equals("use_effect")));
    }

    @Test
    void unknownKindYieldsEmptyList() {
        assertTrue(ApiDocs.fieldsFor("nope").isEmpty());
    }
}
