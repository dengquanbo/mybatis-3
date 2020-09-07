package cn.dqb.mybatisstudy.entity;

import lombok.Data;

@Data
public class Comment {

    private int id;
    private Post post;
    private String name;
    private String comment;
}