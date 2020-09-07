package cn.dqb.mybatisstudy;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;

import cn.dqb.mybatisstudy.entity.Blog;
import cn.dqb.mybatisstudy.mapper.BlogMapper;

public class MybatisMain {
    public static void main(String[] args) throws IOException {
        String resource = "mybatis-config.xml";
        InputStream inputStream = Resources.getResourceAsStream(resource);
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);


        //try (SqlSession session = sqlSessionFactory.openSession()) {
        //    Blog blog = (Blog) session.selectOne("cn.dqb.mybatisstudy.mapper.BlogMapper.selectBlog", 1);
        //    System.out.println(blog);
        //}

        try (SqlSession session = sqlSessionFactory.openSession()) {
            BlogMapper mapper = session.getMapper(BlogMapper.class);
            Blog blog = mapper.selectBlog(1);
            System.out.println(blog);
        }
    }
}
