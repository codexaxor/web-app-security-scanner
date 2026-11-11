package com.company.secscan.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller {@code UUID} parameter that receives the authenticated principal's workspace id (D-093) —
 * the only way a request path learns its workspace. Resolved by {@link CurrentWorkspaceArgumentResolver}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentWorkspace {
}
