package com.pingme.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Stage 3 — Spring Data JPA repository: persistence port for {@link User}.
 *
 * <p>Method names follow the query DSL convention ({@code findByEmail}) so Hibernate generates SQL
 * without hand-written queries for simple lookups.
 */
public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

}
