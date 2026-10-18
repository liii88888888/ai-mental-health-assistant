package com.lqy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lqy.DTO.command.UserLoginCommandDTO;
import com.lqy.DTO.command.UserRegisterCommandDTO;
import com.lqy.DTO.response.UserLoginResponseDTO;
import com.lqy.entity.User;
import com.lqy.enums.UserType;
import com.lqy.exception.BusinessException;
import com.lqy.mapper.UserMapper;
import com.lqy.service.convert.UserConvert;
import com.lqy.util.JwtTokenUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static com.lqy.util.JwtTokenUtil.extractTokenFromRequest;
import static com.lqy.util.JwtTokenUtil.generateToken;

@Service
public class UserService {
    @Resource
    private UserMapper userMapper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 用户不存在时拿来做"假比对"的 BCrypt 哈希。
     *
     * 为什么需要：BCrypt 比对是"故意很慢"的。如果用户不存在时直接返回，
     * 响应会明显比"密码错误"快 —— 攻击者靠**响应时间**就能判断账号是否存在。
     * 所以两种情况都跑一次 BCrypt，把时间差抹平。
     */
    private final String dummyHash = passwordEncoder.encode("dummy-password-for-timing");

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    //登录方法
    public UserLoginResponseDTO login(@Valid UserLoginCommandDTO commandDTO) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, commandDTO.getUsername())
                .or()
                .eq(User::getEmail, commandDTO.getUsername());
        User user = userMapper.selectOne(queryWrapper);

        // ★ 账号/密码不分开报错：分开说等于告诉攻击者"这个账号是存在的"
        //   （也就是账号枚举）。统一成一句话，外部无法区分。
        //   同时两种情况都跑一次 BCrypt，连响应时间也尽量一致。
        boolean passwordOk = passwordEncoder.matches(
                commandDTO.getPassword(),
                user != null ? user.getPassword() : dummyHash);
        if (user == null || !passwordOk) {
            throw new BusinessException("用户名或密码错误");
        }

        //验证用户状态是否正常
        if (!user.isActive()) {
            throw new BusinessException("用户被封禁，无法登录");
        }

        //全部校验通过生成token
        String token = JwtTokenUtil.generateToken(user.getId(), user.getUsername(), user.getUserType());

        //封装登录响应DTO
        UserLoginResponseDTO.UserDetailResponseDTO userInfo = UserConvert.entityToDetailResponse(user);

        return UserConvert.entityToLoginResponse(token, userInfo);
    }

    //注册方法
    public UserLoginResponseDTO.UserDetailResponseDTO register(UserRegisterCommandDTO commandDTO) {

        //验证两次输入密码是否一致
        if (!commandDTO.getPassword().equals(commandDTO.getConfirmPassword())) {
            throw new IllegalArgumentException("两次输入密码不一致");
        }

        //判断用户名是否存在
        LambdaQueryWrapper<User> userNameQuery =  new LambdaQueryWrapper<>();
        userNameQuery.eq(User::getUsername, commandDTO.getUsername());
        if (userMapper.selectCount(userNameQuery) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }

        //检查邮箱是否存在
        LambdaQueryWrapper<User> emailQuery =  new LambdaQueryWrapper<>();
        emailQuery.eq(User::getEmail, commandDTO.getEmail());
        if (userMapper.selectCount(emailQuery) > 0) {
            throw new BusinessException("邮箱已存在");
        }

        //检查手机号是否存在（phone 是选填字段，只在用户填了的情况下才查重）
        if (StringUtils.hasText(commandDTO.getPhone())) {
            LambdaQueryWrapper<User> phoneQuery = new LambdaQueryWrapper<>();
            phoneQuery.eq(User::getPhone, commandDTO.getPhone());
            if (userMapper.selectCount(phoneQuery) > 0) {
                throw new BusinessException("手机号已被注册");
            }
        }

        //验证用户类型是否有效
        if (!UserType.isValidCode(commandDTO.getUserType())) {
            throw new IllegalArgumentException("无效的用户类型");
        }

        //创建用户对象，赋值
        String password = commandDTO.getPassword();
        String encodedPassword = passwordEncoder.encode(password);
        User user = UserConvert.registerCommandToEntity(commandDTO, encodedPassword);

        //插入数据库
        userMapper.insert(user);

        //返回刚注册的用户信息
        return UserConvert.entityToDetailResponse(user);
    }

    //根据用户id查找用户
    public UserLoginResponseDTO.UserDetailResponseDTO getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return UserConvert.entityToDetailResponse(user);
    }


    public void logout(HttpServletRequest request) {
        String token = JwtTokenUtil.extractTokenFromRequest(request);
        // 存入 redis 黑名单（TTL 在 TokenBlacklistService 里按 token 剩余寿命算）
        tokenBlacklistService.addToBlackList(token);
    }
}
