package cn.dqb.mybatisstudy.entity;

import java.util.Date;
import java.util.List;

import lombok.Data;

@Data
public class Post {

    private int id;
    private Author author;
    private Blog blog;
    private Date createdOn;
    private Section section;
    private String subject;
    private String body;
    private List<Comment> comments;
    private List<Tag> tags;
}