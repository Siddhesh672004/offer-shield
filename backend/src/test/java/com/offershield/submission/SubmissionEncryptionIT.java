package com.offershield.submission;

import com.jayway.jsonpath.JsonPath;
import com.offershield.auth.OidcUserProvisioningService;
import com.offershield.college.CollegeRepository;
import com.offershield.common.crypto.TextCipher;
import com.offershield.membership.MembershipRepository;
import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-12 (PRD §14.6): the letter text and the typed contact details are opaque AES-GCM bytes in MySQL, never plaintext;
 * they decrypt only with the submission's own ID as associated data.
 */
class SubmissionEncryptionIT extends IntegrationTest {

    @Autowired
    private OidcUserProvisioningService provisioning;
    @Autowired
    private CollegeRepository colleges;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SubmissionService submissionService;

    @Test
    void textCipher_isOpaqueBytes_andDecryptsWithItsOwnId() throws Exception {
        SubmissionFixtures fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        UUID user = fixtures.user();
        String body = mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey())
                        .param("text", SubmissionFixtures.LETTER)
                        .param("senderEmail", SubmissionFixtures.SENDER)
                        .param("recruiterPhone", SubmissionFixtures.PHONE)
                        .param("upiId", SubmissionFixtures.UPI))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.submissionId"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM submission WHERE id = ?", SubmissionFixtures.idBytes(id));
        byte[] textCipher = (byte[]) row.get("text_cipher");
        byte[] contactsCipher = (byte[]) row.get("contacts_cipher");

        // SELECT HEX(LEFT(text_cipher,16)) FROM submission: random-looking bytes, not "Dear Candidate".
        String hexPrefix = jdbc.queryForObject("SELECT HEX(LEFT(text_cipher,16)) FROM submission WHERE id = ?",
                String.class, SubmissionFixtures.idBytes(id));
        assertThat(hexPrefix).hasSize(32).isNotEqualTo(HexFormat.of().withUpperCase()
                .formatHex("Dear Candidate, ".getBytes(StandardCharsets.UTF_8)));
        assertThat(textCipher).hasSize(TextCipher.IV_BYTES
                + SubmissionFixtures.LETTER.getBytes(StandardCharsets.UTF_8).length + 16);
        assertThat(row.get("key_version")).isEqualTo(TextCipher.CURRENT_KEY_VERSION);

        // No column of the row holds the letter or a contact detail in clear text.
        String everyColumn = row.values().stream()
                .map(value -> value instanceof byte[] bytes ? new String(bytes, StandardCharsets.ISO_8859_1) : String.valueOf(value))
                .reduce("", String::concat);
        assertThat(everyColumn)
                .doesNotContain(SubmissionFixtures.MARKER)
                .doesNotContain("Junior Analyst")
                .doesNotContain("99999")
                .doesNotContain(SubmissionFixtures.UPI)
                .doesNotContain(SubmissionFixtures.SENDER);
        assertThat(contactsCipher).isNotNull();

        SubmissionContent content = submissionService.findContent(id).orElseThrow();
        assertThat(content.text()).isEqualTo(SubmissionFixtures.LETTER);
        assertThat(content.senderEmail()).isEqualTo(SubmissionFixtures.SENDER);
        assertThat(content.recruiterPhone()).isEqualTo(SubmissionFixtures.PHONE);
        assertThat(content.upiId()).isEqualTo(SubmissionFixtures.UPI);
        assertThat(content.senderDomain()).isEqualTo("fictional-hiring.example");
    }

    @Test
    void cipherMovedToAnotherRow_doesNotDecrypt() throws Exception {
        SubmissionFixtures fixtures = new SubmissionFixtures(provisioning, colleges, memberships, mockMvc);
        UUID user = fixtures.user();
        UUID first = submit(fixtures, user, SubmissionFixtures.LETTER);
        UUID second = submit(fixtures, user, "Dear Candidate, a different fictional letter with enough words to pass.");

        byte[] firstCipher = jdbc.queryForObject("SELECT text_cipher FROM submission WHERE id = ?", byte[].class,
                SubmissionFixtures.idBytes(first));
        jdbc.update("UPDATE submission SET text_cipher = ? WHERE id = ?", firstCipher, SubmissionFixtures.idBytes(second));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> submissionService.findContent(second))
                .isInstanceOf(TextCipher.DecryptionFailedException.class);
    }

    private UUID submit(SubmissionFixtures fixtures, UUID user, String text) throws Exception {
        String body = mockMvc.perform(fixtures.upload(user, SubmissionFixtures.newKey()).param("text", text))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.submissionId"));
    }
}
