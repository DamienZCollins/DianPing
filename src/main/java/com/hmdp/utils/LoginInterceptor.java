package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.从ThreadLocal中获取用户
        UserDTO user = UserHolder.getUser();

        // 2.判断用户是否存在
        if (user == null) {
            // 3.不存在，拦截，返回401
            response.setStatus(401);
            return false;
        }

        // 4.存在，放行（用户已由RefreshTokenInterceptor保存到ThreadLocal）
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        // 双重保险：确保ThreadLocal被清理，避免内存泄漏
        UserHolder.removeUser();
    }
}
