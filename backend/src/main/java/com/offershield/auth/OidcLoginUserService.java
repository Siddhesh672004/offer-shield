package com.offershield.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Runs after Spring has validated the Google ID token (signature, issuer, audience, nonce):
 * provisions the user by {@code sub} and returns the session principal with our user ID and role.
 */
@Component
public class OidcLoginUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final OidcUserProvisioningService provisioningService;

    @Autowired
    public OidcLoginUserService(OidcUserProvisioningService provisioningService) {
        this(new OidcUserService(), provisioningService);
    }

    OidcLoginUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, OidcUserProvisioningService provisioningService) {
        this.delegate = delegate;
        this.provisioningService = provisioningService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser googleUser = delegate.loadUser(userRequest);
        ProvisionedUser user = provisioningService.provision(new OidcIdentity(
                googleUser.getSubject(),
                googleUser.getEmail(),
                Boolean.TRUE.equals(googleUser.getEmailVerified()),
                googleUser.getFullName()));
        return new AppOidcUser(user.userId(), user.role(), googleUser.getIdToken(), googleUser.getUserInfo());
    }
}
