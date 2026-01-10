package com.yef.agent.vo;

/**
 * 声明为 public，这样 service 包和 config 包都能访问它
 * 括号里的参数就是它的属性，会自动生成对应的字段
 */
public record StudentResponse(int id, String name, String email) {
}

