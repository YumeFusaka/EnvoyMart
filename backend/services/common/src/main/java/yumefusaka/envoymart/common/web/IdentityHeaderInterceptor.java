package yumefusaka.envoymart.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import yumefusaka.envoymart.common.context.BaseContext;

public class IdentityHeaderInterceptor implements HandlerInterceptor {

    public static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        BaseContext.setCurrentId(request.getHeader(USER_ID_HEADER));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
