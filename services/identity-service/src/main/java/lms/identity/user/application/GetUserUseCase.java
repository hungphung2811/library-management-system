package lms.identity.user.application;

import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserRepository;
import java.util.UUID;

public class GetUserUseCase {

    private final UserRepository repository;

    public GetUserUseCase(UserRepository repository) {
        this.repository = repository;
    }

    public UserResponse execute(UUID id) {
        return repository.findById(id).map(UserResponse::from).orElseThrow(() -> new UserNotFoundException(id));
    }
}
