package com.wuyao.growth.common.web;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 契约 2：所有分页接口统一返回这个结构。
 * 入参统一用 page（从 0 开始）+ size。
 */
public record PageResult<T>(List<T> items, int page, int size, long total, int totalPages) {

    public static <T> PageResult<T> of(Page<T> p) {
        return new PageResult<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    public static <S, T> PageResult<T> of(Page<S> p, List<T> mapped) {
        return new PageResult<>(mapped, p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
