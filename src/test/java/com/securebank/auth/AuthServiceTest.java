package com.securebank.auth;

import com.securebank.common.DuplicateEmailException;
import com.securebank.security.JwtTokenService;
import com.securebank.user.Role;
import com.securebank.user.RoleName;
import com.securebank.user.RoleRepository;
import com.securebank.user.User;
import com.securebank.user.UserRepository;
import com.securebank.user.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenService jwtTokenService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, roleRepository, passwordEncoder, authenticationManager,
                jwtTokenService);
    }

    @Test
    void registerHashesPasswordNormalizesEmailAndAssignsCustomerRole() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(roleRepository.findByName(RoleName.CUSTOMER)).thenReturn(Optional.of(new Role(RoleName.CUSTOMER)));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            return user;
        });

        UserResponse response = authService.register(
                new RegisterRequest("Alice", "Smith", "  Alice@Example.COM ", "Sup3rSecret!"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        User user = saved.getValue();
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getPasswordHash()).isNotEqualTo("Sup3rSecret!").startsWith("$2");
        assertThat(passwordEncoder.matches("Sup3rSecret!", user.getPasswordHash())).isTrue();
        assertThat(response.roles()).containsExactly(RoleName.CUSTOMER);
        assertThat(response.email()).isEqualTo("alice@example.com");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Alice", "Smith", "alice@example.com", "Sup3rSecret!")))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void loginWithInvalidCredentialsDoesNotIssueToken() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void requestToStringNeverContainsPassword() {
        assertThat(new RegisterRequest("A", "B", "a@b.com", "Sup3rSecret!").toString()).doesNotContain("Sup3rSecret!");
        assertThat(new LoginRequest("a@b.com", "Sup3rSecret!").toString()).doesNotContain("Sup3rSecret!");
    }
}
