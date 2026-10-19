package com.offershield.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Runs after Spring has validated the Google ID token (signature, issuer, audience, nonce):
 * provisions the user by {@code sub} and returns the session principal with our user ID and role.
 */
@Component
public class OidcLoginUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginUserService.class);

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final OidcUserProvisioningService provisioningService;

    @Autowired
    public OidcLoginUserService(OidcUserProvisioningService provisioningService, GoogleHttpClients googleHttpClients) {
        this(googleHttpClients.oidcUserService(), provisioningService);
    }

    OidcLoginUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, OidcUserProvisioningService provisioningService) {
        this.delegate = delegate;
        this.provisioningService = provisioningService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser googleUser = delegate.loadUser(userRequest);
        ProvisionedUser user = provision(googleUser);
        return new AppOidcUser(user.userId(), user.role(), googleUser.getIdToken(), googleUser.getUserInfo());
    }

    /**
     * A provisioning failure becomes a normal login failure (redirect to the failure URL). The cause is
     * dropped on purpose: SQL error text can echo the user's sub or email into container logs (D-27).
     */
    private ProvisionedUser provision(OidcUser googleUser) {
        try {
            return provisioningService.provision(new OidcIdentity(
                    googleUser.getSubject(),
                    googleUser.getEmail(),
                    Boolean.TRUE.equals(googleUser.getEmailVerified()),
                    googleUser.getFullName()));
        } catch (RuntimeException ex) {
            log.warn("User provisioning failed type={}", ex.getClass().getSimpleName());
            throw new OAuth2AuthenticationException(new OAuth2Error("provisioning_failed", "Sign-in could not be completed.", null));
        }
    }
}
