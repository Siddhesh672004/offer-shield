package com.offershield.auth;

import com.offershield.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginRedirectTest extends IntegrationTest {

    @Test
    void containsStateNonceAndPkce() throws Exception {
        String location = mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location)
                .startsWith("https://accounts.google.com/")
                .contains("response_type=code")
                .contains("state=")
                .contains("nonce=")
                .contains("code_challenge=")
                .contains("code_challenge_method=S256")
                .contains("openid")
                .contains("redirect_uri=");
    }
}
