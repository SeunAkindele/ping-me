package com.pingme.user;

import com.pingme.user.dto.CreateUserRequest;
import com.pingme.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for the user module.
 *
 * <p>Stage 4 — Controller: translates HTTP to service calls and back. No database access and no
 * business rules beyond request validation.
 *
 * <p>Request flow: HTTP → Controller → {@link UserService} → {@link UserRepository} → PostgreSQL.
 */
@RestController
@RequestMapping("/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
		return userService.createUser(request);
	}

}
