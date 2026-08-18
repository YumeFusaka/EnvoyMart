package yumefusaka.envoymart.authservice.service;

import yumefusaka.envoymart.authservice.model.LoginRequest;
import yumefusaka.envoymart.authservice.model.LoginResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);
}
