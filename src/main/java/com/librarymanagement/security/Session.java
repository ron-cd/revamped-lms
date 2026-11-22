package com.librarymanagement.security;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The signed-in user plus what they may do. Services receive a Session and call {@link #require(Permission)}
 * first, so permissions are enforced even if the UI hides or shows a button by mistake.
 * A session is only valid until it is ended (sign out / expiry) or has been inactive for longer than its timeout.
 */
public final class Session {

    private final long userId;
    private final String username;
    private final String fullName;
    private final String roleName;
    private final Set<Permission> permissions;
    private final Duration timeout;
    private volatile Instant lastActivity;
    private volatile boolean ended;
    private volatile boolean mustChangePassword;

    public Session(long userId, String username, String fullName, String roleName,
                   Set<Permission> permissions, Duration timeout, Instant startedAt, boolean mustChangePassword) {
        this.userId = userId;
        this.username = username;
        this.fullName = fullName;
        this.roleName = roleName;
        this.permissions = permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
        this.timeout = timeout;
        this.lastActivity = startedAt;
        this.mustChangePassword = mustChangePassword;
    }

    public long userId()      { return userId; }
    public String username()  { return username; }
    public String fullName()  { return fullName; }
    public String roleName()  { return roleName; }
    public Duration timeout() { return timeout; }

    /** "ADMINISTRATOR" -> "Administrator" */
    public String roleLabel() {
        String lower = roleName.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public boolean isActive() {
        return !ended;
    }

    /** True after signing in with a temporary password: only changing the password is allowed until then. */
    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    public void markPasswordChanged() {
        mustChangePassword = false;
    }

    /** For operations any signed-in user may do (such as changing their own password). */
    public void requireActive() {
        if (ended) throw new AuthorizationException("Your session has ended. Please sign in again.");
    }

    public boolean has(Permission permission) {
        return !ended && !mustChangePassword && permissions.contains(permission);
    }

    public boolean hasAny(Permission... any) {
        for (Permission p : any) if (has(p)) return true;
        return false;
    }

    public void require(Permission permission) {
        requireActive();
        if (mustChangePassword) throw new AuthorizationException("You must change your temporary password first.");
        if (!permissions.contains(permission)) {
            throw new AuthorizationException("You do not have permission to do that.");
        }
    }

    public void requireAny(Permission... any) {
        requireActive();
        if (mustChangePassword) throw new AuthorizationException("You must change your temporary password first.");
        if (!hasAny(any)) throw new AuthorizationException("You do not have permission to do that.");
    }

    /** Records user activity; keeps the session alive. */
    public void touch(Instant now) {
        lastActivity = now;
    }

    public boolean isExpired(Instant now) {
        return ended || Duration.between(lastActivity, now).compareTo(timeout) >= 0;
    }

    public void end() {
        ended = true;
    }
}
