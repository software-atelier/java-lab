package ch.software_atelier.java_lab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavaLabApplicationTest {
    @Test
    void browserUrlUsesActualPortAndContextPath() throws Exception {
        assertThat(JavaLabApplication.browserUri(49152, "127.0.0.1", "/lab", false))
                .hasToString("http://127.0.0.1:49152/lab/");
    }

    @Test
    void wildcardBindingUsesLoopbackAndRespectsTls() throws Exception {
        assertThat(JavaLabApplication.browserUri(8443, "0.0.0.0", "", true))
                .hasToString("https://127.0.0.1:8443/");
        assertThat(JavaLabApplication.browserUri(8080, "::", "", false))
                .hasToString("http://127.0.0.1:8080/");
    }

    @Test
    void specificBindingIsUsedForBrowser() throws Exception {
        assertThat(JavaLabApplication.browserUri(8080, "::1", "/work", false))
                .hasToString("http://[::1]:8080/work/");
    }
}
