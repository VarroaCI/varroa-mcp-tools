package io.jenkins.plugins.varroamcptools.util;

import hudson.model.Computer;
import hudson.model.Item;
import hudson.security.AccessControlled;
import hudson.security.AccessDeniedException3;
import hudson.security.Permission;
import jenkins.model.Jenkins;

/**
 * Static permission fail-early helper.
 *
 * <p>Checks whether the current authentication context holds
 * {@code permission} on {@code target} and throws an
 * {@link AccessDeniedException3} subclass with a one-line message
 * naming both the missing permission and the target identity if the
 * check fails. This helper never grants access — it only fails early
 * with a clear message when the caller lacks a required permission.
 */
public final class PermissionGuard {

    private PermissionGuard() {
        // utility class — no instantiation
    }

    /**
     * Checks that the current authentication context holds
     * {@code permission} on {@code target}.
     *
     * @param target     the access-controlled object (e.g. {@code Item},
     *                   {@code Computer}, {@code Jenkins})
     * @param permission the permission required
     * @throws AccessDeniedException3 if the current user lacks
     *         {@code permission} on {@code target}, with a one-line
     *         message naming both the permission and the target
     */
    public static void require(AccessControlled target, Permission permission) {
        if (!target.hasPermission(permission)) {
            throw new MissingPermissionException(target, permission);
        }
    }

    // ------------------------------------------------------------------
    // Private nested exception
    // ------------------------------------------------------------------

    /**
     * An {@link AccessDeniedException3} whose {@link #getMessage()} names
     * both the missing permission and the target identity.
     */
    private static final class MissingPermissionException extends AccessDeniedException3 {

        private static final long serialVersionUID = 1L;

        private final String message;

        MissingPermissionException(AccessControlled target, Permission permission) {
            super(Jenkins.getAuthentication2(), permission);
            this.message = permission.group.title + "/" + permission.name + " required for " + identityOf(target);
        }

        @Override
        public String getMessage() {
            return message;
        }

        private static String identityOf(AccessControlled target) {
            if (target instanceof Item) {
                return ((Item) target).getFullName();
            }
            if (target instanceof Computer) {
                return ((Computer) target).getName();
            }
            if (target instanceof Jenkins) {
                return "Jenkins";
            }
            return String.valueOf(target);
        }
    }
}
