package cn.dqb.mybatisstudy.entity;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

@Data
public class Blog implements Serializable {
    private int id;
    private String title;
    private Author author;
    private List<Post> posts;
}
