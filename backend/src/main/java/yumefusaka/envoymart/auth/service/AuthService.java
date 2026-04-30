package yumefusaka.envoymart.auth.service;

import yumefusaka.envoymart.auth.model.request.LoginRequest;
import yumefusaka.envoymart.auth.model.response.LoginResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);
}
