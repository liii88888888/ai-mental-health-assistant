package com.lqy.controller;

import com.lqy.DTO.command.UserLoginCommandDTO;
import com.lqy.DTO.command.UserRegisterCommandDTO;
import com.lqy.DTO.response.UserLoginResponseDTO;
import com.lqy.common.Result;
import com.lqy.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {
    @Autowired
    private UserService userService;

    //登录接口
    @PostMapping("/login")
    public Result<UserLoginResponseDTO> login(@Valid @RequestBody UserLoginCommandDTO commandDTO) {
        UserLoginResponseDTO userLoginResponseDTO = userService.login(commandDTO);
        return Result.ok(userLoginResponseDTO);
    }

    //注册接口
    @PostMapping("/register")
    public Result<UserLoginResponseDTO.UserDetailResponseDTO> register(@Valid @RequestBody UserRegisterCommandDTO commandDTO) {
        UserLoginResponseDTO.UserDetailResponseDTO result = userService.register(commandDTO);
        return Result.ok(result);
    }

    //退出登录接口
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request) {
        userService.logout(request);
        return Result.ok("退出登录成功");
    }
}
