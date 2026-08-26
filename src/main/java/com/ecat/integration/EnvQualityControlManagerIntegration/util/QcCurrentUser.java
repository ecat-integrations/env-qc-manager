package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ruoyi.common.utils.SecurityUtils;

/**
 * 本集成内读取当前登录用户名（RuoYi {@code user_name}），无登录上下文时返回空串。
 */
public final class QcCurrentUser {

    private QcCurrentUser() {
    }

    public static String usernameOrEmpty() {
        try {
            String u = SecurityUtils.getUsername();
            if (u == null) {
                return "";
            }
            String t = u.trim();
            if (t.isEmpty() || "anonymousUser".equalsIgnoreCase(t)) {
                return "";
            }
            return t;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
