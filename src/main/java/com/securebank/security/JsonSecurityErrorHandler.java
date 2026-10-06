package com.securebank.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.common.ErrorCode;
import com.securebank.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes 401/403 responses raised by the security filter chain in the same JSON format as the
 * rest of the API.
 */
@Component
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final ObjectMapper objectMapper;

    public JsonSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // Sets the WWW-Authenticate header and status as required by RFC 6750.
        bearerEntryPoint.commence(request, response, authException);
        String message = authException instanceof InvalidBearerTokenException
                ? "Access token is invalid or expired"
                : "Authentication is required";
        write(response, ErrorResponse.of(ErrorCode.UNAUTHORIZED, message, request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        write(response, ErrorResponse.of(ErrorCode.FORBIDDEN,
                "You do not have permission to perform this action", request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ErrorResponse body) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
