package com.nomadix.gateway.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Convertit les roles du realm Keycloak en autorites Spring Security.
 *
 * Keycloak place les roles dans le claim :
 * <pre>
 * "realm_access": { "roles": ["TRAVELER", "ADMIN"] }
 * </pre>
 * Or Spring Security attend des autorites prefixees par {@code ROLE_} pour que
 * {@code hasRole("ADMIN")} fonctionne. D'ou la transformation
 * {@code ADMIN -> ROLE_ADMIN}.
 */
public final class KeycloakRoles {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";
    private static final String PREFIX = "ROLE_";

    private KeycloakRoles() {
    }

    /** Utilisable directement comme {@code JwtGrantedAuthoritiesConverter}. */
    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);
        if (realmAccess == null) {
            return List.of();
        }
        Object rawRoles = realmAccess.get(ROLES);
        if (!(rawRoles instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(PREFIX + role))
                .toList();
    }

    /** Version "converter" pratique pour les configurations declaratives. */
    public static Converter<Jwt, Collection<GrantedAuthority>> converter() {
        return KeycloakRoles::authorities;
    }
}
