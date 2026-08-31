package com.dashboard.api;

import com.dashboard.api.security.ClientKeyMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientKeyMapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClientKeyMap map(String raw) {
        return new ClientKeyMap(MAPPER, raw, null);
    }

    private ClientKeyMap mapWithSeed(String raw, String seed) {
        return new ClientKeyMap(MAPPER, raw, seed);
    }

    @Test
    void parsesAJsonObjectOfNameToToken() {
        ClientKeyMap keys = map("{\"continuum\":\"tok_c\",\"budget-cli\":\"tok_b\"}");

        assertThat(keys.keyFor("continuum")).contains("tok_c");
        assertThat(keys.keyFor("budget-cli")).contains("tok_b");
        assertThat(keys.size()).isEqualTo(2);
    }

    @Test
    void parsesCsvNameEqualsTokenPairs() {
        ClientKeyMap keys = map("continuum=tok_c, budget-cli = tok_b ");

        assertThat(keys.keyFor("continuum")).contains("tok_c");
        assertThat(keys.keyFor("budget-cli")).contains("tok_b");
    }

    @Test
    void blankOrMissingYieldsAnEmptyMap() {
        assertThat(map("").size()).isZero();
        assertThat(map("   ").size()).isZero();
        assertThat(map(null).size()).isZero();
    }

    @Test
    void unknownClientHasNoKeyWithoutASeed() {
        assertThat(map("continuum=tok_c").keyFor("nope")).isEmpty();
        assertThat(map("continuum=tok_c").canDerive()).isFalse();
    }

    @Test
    void withASeedAnUnpinnedClientGetsADeterministicDerivedKey() {
        ClientKeyMap keys = mapWithSeed("continuum=tok_c", "root-seed-value");

        assertThat(keys.keyFor("continuum")).contains("tok_c");           // explicit still wins
        String derived = keys.keyFor("new-app").orElseThrow();
        assertThat(derived).startsWith("mono_k1_");
        assertThat(keys.keyFor("new-app")).contains(derived);            // stable
        assertThat(mapWithSeed("", "root-seed-value").keyFor("new-app")).contains(derived); // seed-only
        assertThat(keys.deriveKey("other-app")).isNotEqualTo(keys.deriveKey("new-app"));
    }

    @Test
    void malformedJsonFailsFast() {
        assertThatThrownBy(() -> map("{\"continuum\": }"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void aCsvEntryWithoutAnEqualsFailsFast() {
        assertThatThrownBy(() -> map("continuum tok_c"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name=token");
    }
}
