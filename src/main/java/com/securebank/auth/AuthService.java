package com.securebank.auth;

import com.securebank.common.DuplicateEmailException;
import com.securebank.common.UserNotFoundException;
import com.securebank.security.JwtTokenService;
import com.securebank.user.Role;
import com.securebank.user.RoleName;
import com.securebank.user.RoleRepository;
import com.securebank.user.User;
import com.securebank.user.UserRepository;
import com.securebank.user.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenService = jwtTokenService;
    }

    /** Registers a new user. Self-registration always grants the CUSTOMER role only. */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        Role customerRole = roleRepository.findByName(RoleName.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role is missing"));

        User user = new User(request.firstName().trim(), request.lastName().trim(), email,
                passwordEncoder.encode(request.password()));
        user.addRole(customerRole);
        try {
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException ex) {
            // Another request registered the same email between the check and the insert.
            throw new DuplicateEmailException();
        }
    }

    /** Verifies credentials and issues an access token. Failures surface as BadCredentialsException. */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        User user = userRepository.findByEmail(email).orElseThrow(UserNotFoundException::new);
        return jwtTokenService.issueToken(user);
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
