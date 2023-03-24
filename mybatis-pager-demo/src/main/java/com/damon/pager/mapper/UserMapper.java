package com.damon.pager.mapper;

import com.damon.pager.pojo.User;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserMapper {

    @Select("select * from t_user")
    List<User> getUserPageable(com.damon.pager.utils.CustomPager pager);

    @Insert("insert into t_user (id,name,age,address) values (#{id},#{name},#{age},#{address});")    void save(User user);

    @Delete("delete from t_user")
    void deleteAll();

}
