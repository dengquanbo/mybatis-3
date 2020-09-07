package cn.dqb.mybatisstudy.mapper;

import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.CacheNamespaceRef;
import org.apache.ibatis.annotations.Select;

import cn.dqb.mybatisstudy.entity.Blog;


public interface BlogMapper {
    //@Select(value = {"select * from stu", "1"})
    Blog selectBlog(int i);
}
