package com.dashboard.api;

import com.dashboard.api.security.ClientKeyMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientKeyMapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClientKeyMap map(String raw) {
        return new ClientKeyMap(MAPPER, raw);
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
    void unknownClientHasNoKey() {
        assertThat(map("continuum=tok_c").keyFor("nope")).isEmpty();
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
