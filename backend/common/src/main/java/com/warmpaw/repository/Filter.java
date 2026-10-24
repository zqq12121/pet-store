package com.warmpaw.repository;

import java.util.Collection;
import java.util.List;

/**
 * 单表查询的一个条件；多个条件之间是 <b>AND</b>。
 *
 * <p><b>为什么不用拼接 SQL 字符串</b>：{@link RelationalRepository} 的安全边界是
 * "只有白名单里的标识符能进 SQL，业务值一律参数绑定"。如果开放 {@code where} 字符串参数，
 * 这个边界就没了——调用方（或它上游的请求参数）随时能把任意 SQL 片段拼进来。
 * 用结构化条件可以让校验发生在构造点：{@code field} 必须在模型里存在，
 * {@code op} 只能是枚举值，{@code value} 永远走 {@code ?}。
 *
 * <p><b>为什么 {@code field} 是业务字段名而不是列名</b>：调用方（Service 层）只认识
 * {@code ownerId}、{@code createdAt} 这些业务名，列名映射由模型负责。
 * 传列名等于让业务层了解数据库结构，也绕过了白名单校验。
 *
 * <p><b>不支持 OR</b>：目前只有"关键词在多个字段里任选其一"这一种 OR 需求，
 * 用 {@code RelationalRepository#list} 的 {@code keywordFields} 参数单独表达。
 * 一旦引入通用的条件树，拼 SQL 的复杂度会远超收益。
 */
public record Filter(String field, Op op, Object value) {

  /** 支持的操作符；每增加一个都必须在仓储层补齐对应分支，否则会落到 {@code default} 报错。 */
  public enum Op {
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
    IN,
    /** 值需自带 {@code %} 通配符，本层不做转义——调用方要自己处理 {@code %} 与 {@code _}。 */
    LIKE,
    NOT_NULL
  }

  /**
   * 构造时就把非法组合挡掉。
   *
   * <p>{@code IN} 必须是集合：否则运行到拼 SQL 才报 {@code ClassCastException}，
   * 堆栈指向仓储层，排查时会误以为是框架问题。
   */
  public Filter {
    if (field == null || field.isBlank()) {
      throw new IllegalArgumentException("Filter 字段名不能为空");
    }
    if (op == null) throw new IllegalArgumentException("Filter 操作符不能为空：" + field);
    if (op == Op.IN && !(value instanceof Collection<?>)) {
      throw new IllegalArgumentException("IN 操作需要集合值：" + field);
    }
    if (op != Op.NOT_NULL && value == null) {
      throw new IllegalArgumentException("该操作需要非空值：" + field);
    }
  }

  /** 相等。 */
  public static Filter eq(String field, Object value) {
    return new Filter(field, Op.EQ, value);
  }

  /** 不等。注意 SQL 里 {@code NULL <> ?} 恒为 NULL，null 值请用 {@link #notNull} 单独判断。 */
  public static Filter ne(String field, Object value) {
    return new Filter(field, Op.NE, value);
  }

  /** 大于。 */
  public static Filter gt(String field, Object value) {
    return new Filter(field, Op.GT, value);
  }

  /** 大于等于。 */
  public static Filter ge(String field, Object value) {
    return new Filter(field, Op.GE, value);
  }

  /** 小于。 */
  public static Filter lt(String field, Object value) {
    return new Filter(field, Op.LT, value);
  }

  /** 小于等于。 */
  public static Filter le(String field, Object value) {
    return new Filter(field, Op.LE, value);
  }

  /**
   * 落在集合内。
   *
   * <p>空集合<b>不会</b>被当成"不过滤"，而是翻译成 {@code 1=0}（永不匹配）：
   * 业务上"没有收藏任何内容"应该得到空列表，而不是全部内容。
   */
  public static Filter in(String field, Collection<?> values) {
    return new Filter(field, Op.IN, List.copyOf(values));
  }

  /** 模糊匹配，值需自带 {@code %}。 */
  public static Filter like(String field, String pattern) {
    return new Filter(field, Op.LIKE, pattern);
  }

  /** 非空判断，如"存在支付异常的单"。 */
  public static Filter notNull(String field) {
    return new Filter(field, Op.NOT_NULL, null);
  }
}
