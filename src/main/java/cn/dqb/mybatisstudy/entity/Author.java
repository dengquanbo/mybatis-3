package cn.dqb.mybatisstudy.entity;

import java.io.Serializable;

import lombok.Data;

@Data
public class Author implements Serializable {

    protected int id;
    protected String username;
    protected String password;
    protected String email;
    protected String bio;
    protected Section favouriteSection;
}