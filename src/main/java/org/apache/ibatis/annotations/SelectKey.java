/**
 * Copyright 2009-2016 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.ibatis.annotations;

import org.apache.ibatis.mapping.StatementType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Clinton Begin
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SelectKey {
    /**
     * 表示定义的子查询语句
     */
    String[] statement();

    /**
     * 属性名
     */
    String keyProperty();

    /**
     * 数据库列名
     */
    String keyColumn() default "";

    /**
     * 表示在之前执行，boolean类型的,所以为true
     */
    boolean before();

    /**
     * 表示查询返回的类型
     */
    Class<?> resultType();

    /**
     * 默认使用 {@link java.sql.PreparedStatement}
     */
    StatementType statementType() default StatementType.PREPARED;
}
