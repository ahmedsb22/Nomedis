package com.nomadix.traveler.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Convertit {@code realm_access.roles} (Keycloak) en autorites Spring Security
 * prefixees {@code ROLE_} pour que {@code hasRole("ADMIN")} fonctionne.
 *
 * <p>Ce convertisseur est duplique dans chaque service Java : le projet n'a pas
 * de module parent partage, et 25 lignes explicites valent mieux qu'un module
 * technique supplementaire a maintenir.</p>
 */
public final class KeycloakRoles {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";
    private static final String PREFIX = "ROLE_";

    private KeycloakRoles() {
    }

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

    public static Converter<Jwt, Collection<GrantedAuthority>> converter() {
        return KeycloakRoles::authorities;
    }
}
