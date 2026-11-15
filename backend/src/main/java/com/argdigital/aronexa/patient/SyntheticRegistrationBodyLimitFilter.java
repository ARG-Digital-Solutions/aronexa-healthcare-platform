package com.argdigital.aronexa.patient;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Retains the existing isolated synthetic profiles and ingress cap. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile("test & (c2-synthetic | registration-candidate) & !dev & !prod")
final class SyntheticRegistrationBodyLimitFilter extends RegistrationBodyLimitFilter {}
