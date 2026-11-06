package com.pingme.user;

import com.pingme.common.DuplicateResourceException;
import com.pingme.common.ResourceNotFoundException;
import com.pingme.user.dto.CreateUserRequest;
import com.pingme.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for user lifecycle operations.
 *
 * <p>Stage 4 — Service layer: owns orchestration and transaction boundaries. Controllers delegate
 * here; this class delegates persistence to {@link UserRepository} and keeps domain rules on
 * {@link User}.
 */
@Service
public class UserService {

	private final UserRepository userRepository;

	public UserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Transactional
	public UserResponse createUser(CreateUserRequest request) {
		userRepository.findByEmail(request.email().trim().toLowerCase()).ifPresent(existing -> {
			throw new DuplicateResourceException("User with email already exists: " + existing.getEmail());
		});

		User user = User.create(request.name(), request.email());
		User saved = userRepository.save(user);
		return UserResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public User requireUser(Long userId) {
		return userRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
	}

}
