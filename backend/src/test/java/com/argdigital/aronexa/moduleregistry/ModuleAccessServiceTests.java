package com.argdigital.aronexa.moduleregistry;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.auth.TenantSqlScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModuleAccessServiceTests {
    private TenantAuthorization authorization;
    private TenantSqlScope scope;
    private JdbcTemplate jdbc;
    private ModuleAccessService service;
    private UUID tenant;

    @BeforeEach
    void setup() {
        authorization = mock(TenantAuthorization.class);
        scope = mock(TenantSqlScope.class);
        jdbc = mock(JdbcTemplate.class);
        service = new ModuleAccessService(authorization, scope, jdbc);
        tenant = UUID.randomUUID();
        when(authorization.currentPrincipal()).thenReturn(
                new AuthPrincipal(UUID.randomUUID(),tenant,UUID.randomUUID(),
                        "synthetic",false,List.of("PLATFORM_ADMIN"),List.of()));
    }

    @Test
    void deniesMissingOrInvalidModuleWithoutQueryingDatabase() {
        for (String input : new String[]{ "", "opd", "../OPD", "OPD;DROP", " " }) {
            assertThatThrownBy(() -> service.requireEnabled(input))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> service.requireEnabled(null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(scope,jdbc);
    }

    @Test
    void deniesWhenNoMatchingEnabledRow() {
        when(jdbc.queryForList(anyString(),eq(Integer.class),eq(tenant),eq("OPD")))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.requireEnabled("OPD"))
                .isInstanceOf(AccessDeniedException.class);
        verify(scope).bindVerifiedTenant();
    }

    @Test
    void acceptsOnlyDatabaseConfirmedEnabledRow() {
        when(jdbc.queryForList(anyString(),eq(Integer.class),eq(tenant),eq("OPD")))
                .thenReturn(List.of(1));
        assertThatCode(() -> service.requireEnabled("OPD")).doesNotThrowAnyException();
        verify(scope).bindVerifiedTenant();
    }

    @Test
    void passwordChangeRequiredAlwaysDeniesWithoutDatabaseAccess() {
        when(authorization.currentPrincipal()).thenReturn(
                new AuthPrincipal(UUID.randomUUID(),tenant,UUID.randomUUID(),
                        "synthetic",true,List.of("PLATFORM_ADMIN"),List.of()));
        assertThatThrownBy(() -> service.requireEnabled("OPD"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(scope,jdbc);
    }

    @Test
    void missingAuthenticationFailsClosed() {
        when(authorization.currentPrincipal()).thenThrow(
                new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("missing"));
        assertThatThrownBy(() -> service.requireEnabled("OPD"))
                .isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(scope,jdbc);
    }
}
