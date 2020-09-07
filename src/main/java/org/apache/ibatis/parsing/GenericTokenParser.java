/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.parsing;

/**
 * 通用的token解析器
 *
 * @author Clinton Begin
 */
public class GenericTokenParser {

    /**
     * 开始的 Token 字符串，一般传入的为 ${
     */
    private final String openToken;

    /**
     * 结束的 Token 字符串，一般传入的为 }
     */
    private final String closeToken;

    /**
     * 解析后，通过该 handler 获取值
     */
    private final TokenHandler handler;

    public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
        this.openToken = openToken;
        this.closeToken = closeToken;
        this.handler = handler;
    }

    public String parse(String text) {
        // 为空判断
        if (text == null || text.isEmpty()) {
            return "";
        }
        // search open token
        // 从下标 0 开始寻找 openToken 第一次出现位置
        int start = text.indexOf(openToken, 0);

        // 找不到，说明不包含占位符，直接返回
        if (start == -1) {
            return text;
        }

        // 一个字符，一个字符比较
        char[] src = text.toCharArray();
        int offset = 0;
        final StringBuilder builder = new StringBuilder();
        StringBuilder expression = null;
        while (start > -1) {
            // 转义字符
            if (start > 0 && src[start - 1] == '\\') {
                // this open token is escaped. remove the backslash and continue.
                builder.append(src, offset, start - offset - 1).append(openToken);
                offset = start + openToken.length();
            } else {
                // found open token. let's search close token.
                // 创建/重置 expression 对象
                if (expression == null) {
                    expression = new StringBuilder();
                } else {
                    expression.setLength(0);
                }
                // 添加 offset 和 openToken 之间的内容，添加到 builder 中
                builder.append(src, offset, start - offset);
                offset = start + openToken.length();
                int end = text.indexOf(closeToken, offset);
                while (end > -1) {
                    if (end > offset && src[end - 1] == '\\') {
                        // this close token is escaped. remove the backslash and continue.
                        // 因为 endToken 前面一个位置是 \ 转义字符，所以忽略 \
                        // 添加 [offset, end - offset - 1] 和 endToken 的内容，添加到 builder 中
                        expression.append(src, offset, end - offset - 1).append(closeToken);
                        // 修改 offset
                        offset = end + closeToken.length();

                        // 继续，寻找结束的 closeToken 的位置
                        end = text.indexOf(closeToken, offset);
                    } else {
                        // 非转义
                        // 添加 [offset, end - offset] 的内容，添加到 builder 中
                        expression.append(src, offset, end - offset);
                        offset = end + closeToken.length();
                        break;
                    }
                }
                if (end == -1) {
                    // close token was not found.
                    builder.append(src, start, src.length - start);
                    offset = src.length;
                } else {
                    builder.append(handler.handleToken(expression.toString()));
                    offset = end + closeToken.length();
                }
            }
            start = text.indexOf(openToken, offset);
        }
        if (offset < src.length) {
            builder.append(src, offset, src.length - offset);
        }
        return builder.toString();
    }
}
