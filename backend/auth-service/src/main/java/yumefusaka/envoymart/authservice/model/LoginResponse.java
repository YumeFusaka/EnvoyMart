package yumefusaka.envoymart.authservice.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {

    private String token;
    private UserProfile user;
}
