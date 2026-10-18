package com.lqy.security;

import lombok.Data;
import lombok.Getter;

@Getter
public class LoginUser {
    private final Long userId;
    private final String username;
    private final Integer roleType;

    public LoginUser(Long userId, String username, Integer roleType) {
        this.userId = userId;
        this.username = username;
        this.roleType = roleType;
    }
}