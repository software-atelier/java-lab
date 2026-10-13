package ch.software_atelier.java_lab.display;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class FormsValidationTest {
    private static void load(String json) {
        Forms.loadBase64(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void validatesTypedValuesAndExposesMultiselect() {
        Forms.form("details")
            .email("testEmail", "Email").required("testEmail")
            .date("testDate", "Date")
            .number("testAge", "Age", 18).range("testAge", 18, 120)
            .multiselect("testTags", "Tags", "Java", "CSV");
        load("{\"testEmail\":\"ada@example.org\",\"testDate\":\"2026-10-13\",\"testAge\":28,\"testTags\":[\"Java\",\"CSV\"]}");
        assertThat(Forms.text("testEmail")).isEqualTo("ada@example.org");
        assertThat(Forms.list("testTags")).containsExactly("Java", "CSV");
        assertThat(Forms.number("testAge")).isEqualTo(28);
        assertThatThrownBy(() -> load("{\"testEmail\":\"bad\"}"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> load("{\"testAge\":28}"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> load("{\"testAge\":121}"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> load("{\"testTags\":[\"Other\"]}"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
