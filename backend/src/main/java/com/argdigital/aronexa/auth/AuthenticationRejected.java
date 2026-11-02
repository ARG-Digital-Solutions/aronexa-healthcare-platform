package com.argdigital.aronexa.auth;

/** Generic rejection: do not reveal whether the user, tenant or password is wrong. */
public class AuthenticationRejected extends RuntimeException {
    public AuthenticationRejected() { super("Invalid credentials or account unavailable"); }
}
