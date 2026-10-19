package com.offershield.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPropertiesTest {

    @Test
    void isAdmin_listedEmail_true() {
        AdminProperties properties = new AdminProperties(List.of("admin@example.com"));

        assertThat(properties.isAdmin("admin@example.com")).isTrue();
    }

    @Test
    void isAdmin_differentCaseAndWhitespace_true() {
        AdminProperties properties = new AdminProperties(List.of("  Admin@Example.COM "));

        assertThat(properties.isAdmin(" admin@example.com\t")).isTrue();
        assertThat(properties.isAdmin("ADMIN@EXAMPLE.COM")).isTrue();
    }

    @Test
    void isAdmin_unlistedEmail_false() {
        AdminProperties properties = new AdminProperties(List.of("admin@example.com"));

        assertThat(properties.isAdmin("student@example.com")).isFalse();
    }

    @Test
    void isAdmin_nullList_false() {
        AdminProperties properties = new AdminProperties(null);

        assertThat(properties.isAdmin("admin@example.com")).isFalse();
    }

    @Test
    void isAdmin_blankEntriesAndBlankEmail_false() {
        AdminProperties properties = new AdminProperties(List.of("", "   "));

        assertThat(properties.isAdmin("")).isFalse();
        assertThat(properties.isAdmin("   ")).isFalse();
        assertThat(properties.isAdmin(null)).isFalse();
    }

    @Test
    void bind_commaSeparatedEnvValue_listsBothEmails() {
        AdminProperties properties = bind("offershield.admin.emails", "a@example.com, B@Example.com");

        assertThat(properties.isAdmin("a@example.com")).isTrue();
        assertThat(properties.isAdmin("b@example.com")).isTrue();
        assertThat(properties.isAdmin("c@example.com")).isFalse();
    }

    @Test
    void bind_emptyEnvValue_noAdmins() {
        AdminProperties properties = bind("offershield.admin.emails", "");

        assertThat(properties.isAdmin("a@example.com")).isFalse();
    }

    private static AdminProperties bind(String key, String value) {
        return new Binder(new MapConfigurationPropertySource(Map.of(key, value)))
                .bind("offershield.admin", AdminProperties.class)
                .orElseGet(() -> new AdminProperties(null));
    }
}
