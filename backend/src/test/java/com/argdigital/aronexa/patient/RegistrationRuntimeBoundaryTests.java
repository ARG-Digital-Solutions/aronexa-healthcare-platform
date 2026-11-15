package com.argdigital.aronexa.patient;

import com.argdigital.aronexa.auth.AuthPrincipal;
import com.argdigital.aronexa.auth.TenantAuthorization;
import com.argdigital.aronexa.moduleregistry.ModuleAccessService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated bean/adaptor checks; real SQL and bearer verification are tested separately. */
class RegistrationRuntimeBoundaryTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BoundaryConfiguration.class);

    @TestConfiguration(proxyBeanMethods=false)
    @ComponentScan(basePackages="com.argdigital.aronexa.patient",useDefaultFilters=false,
        includeFilters=@ComponentScan.Filter(type=FilterType.REGEX,
            pattern="com\\.argdigital\\.aronexa\\.patient\\.RegistrationHttpController"))
    static class BoundaryConfiguration {
        @Bean TenantAuthorization authorization() { return new TenantAuthorization(); }
        // This unit test isolates the unavailable-writer HTTP mapping only.
        // PostgreSQL-backed module authorization is exercised in the HTTP suite.
        @Bean ModuleAccessService modules() { return mock(ModuleAccessService.class); }
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void absentOrFalseActivationCreatesNoHttpController() {
        runner.run(c -> assertThat(c.getBeansWithAnnotation(RestController.class)).isEmpty());
        runner.withPropertyValues("aronexa.patient.registration.reviewed-activation=false")
                .run(c -> assertThat(c.getBeansWithAnnotation(RestController.class)).isEmpty());
    }

    @Test void syntheticProfilesDoNotCreateASecondRegistrationController() {
        for (String profile : List.of("c2-synthetic","registration-candidate")) {
            runner.withPropertyValues("aronexa.patient.registration.reviewed-activation=true",
                    "spring.profiles.active="+profile)
                .run(c -> assertThat(c.getBeansWithAnnotation(RestController.class)).isEmpty());
        }
    }

    @Test void explicitActivationWithoutWriterIs503AfterPermissionAndModuleChecks() {
        runner.withPropertyValues("aronexa.patient.registration.reviewed-activation=true")
            .run(c -> {
                assertThat(c.getBeansWithAnnotation(RestController.class)).hasSize(1);
                assertThat(c.getBeansOfType(RegistrationWriteExecutor.class)).isEmpty();
                var controller=c.getBeansWithAnnotation(RestController.class).values().iterator().next();
                var mvc=MockMvcBuilders.standaloneSetup(controller).build();
                authenticate(true, false);
                mvc.perform(post(path()).header("Idempotency-Key",key())
                        .contentType("application/json").content(intake()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(content().json("{\"code\":\"REGISTRATION_WRITER_NOT_ACTIVATED\"}"));
                verify(c.getBean(ModuleAccessService.class)).requireEnabled("PATIENT_REGISTRATION");
            });
    }

    @Test void permissionAndForcedPasswordChangeDenyBeforeMissingWriter() {
        runner.withPropertyValues("aronexa.patient.registration.reviewed-activation=true")
            .run(c -> {
                assertThat(c.getBeansWithAnnotation(RestController.class)).hasSize(1);
                var mvc=MockMvcBuilders.standaloneSetup(c.getBeansWithAnnotation(RestController.class)
                        .values().iterator().next()).build();
                for (boolean mustChange : List.of(false,true)) {
                    authenticate(mustChange, mustChange);
                    mvc.perform(post(path()).header("Idempotency-Key",key())
                            .contentType("application/json").content(intake()))
                        .andExpect(status().isForbidden())
                        .andExpect(header().string("Cache-Control","no-store"))
                        .andExpect(content().json("{\"code\":\"REGISTRATION_DENIED\"}"));
                }
                verifyNoInteractions(c.getBean(ModuleAccessService.class));
            });
    }

    private static void authenticate(boolean permitted, boolean mustChange) {
        var principal=new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "fictional-staff",mustChange,List.of("RECEPTION"),
            permitted?List.of("PATIENT_REGISTER"):List.of(),UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal,"",List.of()));
    }
    private static String path() { return "/api/v1/branches/"+UUID.randomUUID()+"/patient-registrations"; }
    private static String key() { return UUID.randomUUID().toString().replace("-","").repeat(2); }
    private static String intake() { return "{\"displayName\":\"Fictional Boundary\",\"birthDatePrecision\":\"UNKNOWN\",\"dateOfBirth\":null}"; }
}
