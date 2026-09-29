package yumefusaka.envoymart.authservice.service;

import yumefusaka.envoymart.authservice.model.LoginRequest;
import yumefusaka.envoymart.authservice.model.LoginResponse;
import yumefusaka.envoymart.authservice.model.RegisterRequest;

public interface AuthService {

    /**
     * 注册并直接签发 token——注册完还要再登一次是多余的往返，
     * 而且会让前端在两次请求之间短暂处于「有账号但没凭据」的状态。
     */
    LoginResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);
}
