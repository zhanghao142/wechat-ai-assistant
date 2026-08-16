package com.wechatai.ai.mapper;

import com.wechatai.ai.entity.AppUserEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppUserMapper {

    @Select("""
        SELECT user_id, email, created_at, updated_at
        FROM app_user
        WHERE email = #{email}
        LIMIT 1
        """)
    AppUserEntity findByEmail(String email);

    @Select("""
        SELECT user_id, email, created_at, updated_at
        FROM app_user
        WHERE user_id = #{userId}
        LIMIT 1
        """)
    AppUserEntity findByUserId(String userId);

    @Insert("""
        INSERT INTO app_user (user_id, email, created_at, updated_at)
        VALUES (#{userId}, #{email}, NOW(), NOW())
        """)
    int insert(AppUserEntity entity);
}
