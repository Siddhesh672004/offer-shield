package com.offershield.auth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.List;
import java.util.UUID;

/** Session principal: the Google OIDC user plus our own user ID and role (never taken from request headers). */
public class AppOidcUser extends DefaultOidcUser {

    private final UUID userId;
    private final Role role;

    public AppOidcUser(UUID userId, Role role, OidcIdToken idToken, OidcUserInfo userInfo) {
        super(List.of(new SimpleGrantedAuthority(role.authority())), idToken, userInfo, IdTokenClaimNames.SUB);
        this.userId = userId;
        this.role = role;
    }

    public UUID userId() {
        return userId;
    }

    public Role role() {
        return role;
    }
}
