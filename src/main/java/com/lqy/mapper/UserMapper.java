package com.lqy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lqy.entity.User;
import org.apache.ibatis.annotations.Mapper;


@Mapper
public interface UserMapper extends BaseMapper<User> {

}
