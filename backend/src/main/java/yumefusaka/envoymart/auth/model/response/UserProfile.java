package yumefusaka.envoymart.auth.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserProfile {

    private String id;
    private String username;
    private String nickname;
    private String roleName;
    private String avatar;
}
