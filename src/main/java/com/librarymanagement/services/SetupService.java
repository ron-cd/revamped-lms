package com.librarymanagement.services;

import com.librarymanagement.database.DatabaseManager;
import com.librarymanagement.database.repositories.AuditRepository;
import com.librarymanagement.database.repositories.UserRepository;
import com.librarymanagement.security.PasswordHasher;
import com.librarymanagement.security.PasswordPolicy;
import com.librarymanagement.validation.BusinessRuleException;
import com.librarymanagement.validation.UserRules;
import com.librarymanagement.validation.ValidationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * First-run setup. The application cannot be used until an administrator exists,
 * and there is no default account or password.
 */
public class SetupService {

    private static final int ADMINISTRATOR_ROLE_ID = 1;

    private final DatabaseManager db;
    private final UserRepository users;
    private final AuditRepository audit;
    private final PasswordHasher hasher;

    public SetupService(DatabaseManager db, UserRepository users, AuditRepository audit, PasswordHasher hasher) {
        this.db = db;
        this.users = users;
        this.audit = audit;
        this.hasher = hasher;
    }

    public boolean isSetupRequired() {
        return db.inTransactionless(c -> users.count(c) == 0);
    }

    /**
     * Creates the first administrator. The password arrays are wiped before returning.
     * @return the new user's id
     */
    public long createInitialAdministrator(String username, String fullName, char[] password, char[] confirm) {
        try {
            String u = username == null ? "" : username.trim();
            String name = fullName == null ? "" : fullName.trim();

            List<String> problems = new ArrayList<>(UserRules.check(u, name));
            problems.addAll(PasswordPolicy.check(password, u));
            if (confirm == null || !Arrays.equals(password, confirm)) problems.add("Passwords do not match.");
            if (!problems.isEmpty()) throw new ValidationException(problems);

            String hash = hasher.hash(password);

            return db.inTransaction(c -> {
                // Checked inside the transaction so two launches cannot both create an admin.
                if (users.count(c) > 0) throw new BusinessRuleException("Setup has already been completed.");
                long id = users.insert(c, u, hash, name, ADMINISTRATOR_ROLE_ID);
                audit.append(c, id, "INITIAL_ADMIN_CREATED", "user", String.valueOf(id), "username=" + u);
                return id;
            });
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirm != null) Arrays.fill(confirm, '\0');
        }
    }
}
