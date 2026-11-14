package lms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class PermissionJwtConverterTest {

    private Jwt jwt(String department) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim(JwtClaims.USERNAME, "alice")
                .claim(JwtClaims.PERMISSIONS, List.of(Permissions.BORROW_BOOK, Permissions.BOOK_VIEW))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (department != null) builder.claim(JwtClaims.DEPARTMENT_ID, department);
        return builder.build();
    }

    @Test
    void claimPermissionsTroThanhAuthorityKhongPrefix() {
        var auth = PermissionJwtConverter.create().convert(jwt(null));

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("BORROW_BOOK", "BOOK_VIEW");
    }

    @Test
    void currentUserDocDepartmentTuClaim() {
        UUID department = UUID.randomUUID();

        CurrentUser user = CurrentUser.from(jwt(department.toString()));

        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.inDepartment(department)).isTrue();
        assertThat(user.inDepartment(UUID.randomUUID())).isFalse();
        assertThat(CurrentUser.from(jwt(null)).inDepartment(department)).isFalse();
    }
}
