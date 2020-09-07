/**
 * Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.builder.xml;

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

    private final MapperBuilderAssistant builderAssistant;
    /**
     * 当前 XML 节点，例如：<select />、<insert />、<update />、<delete /> 标签
     */
    private final XNode context;

    /**
     * 要求的 databaseId
     */
    private final String requiredDatabaseId;

    public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
        this(configuration, builderAssistant, context, null);
    }

    /**
     * @param configuration    配置
     * @param builderAssistant Mapper解析辅助类
     * @param context          node节点
     * @param databaseId       要求的 databaseId
     */
    public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context,
                               String databaseId) {
        super(configuration);
        this.builderAssistant = builderAssistant;
        this.context = context;
        this.requiredDatabaseId = databaseId;
    }

    /**
     * 解析{@code <select> <insert /> <update /> <delete />}
     */
    public void parseStatementNode() {
        // <1> 获得 id 属性，编号。
        String id = context.getStringAttribute("id");

        // <2> 获得 databaseId ， 判断 databaseId 是否匹配
        String databaseId = context.getStringAttribute("databaseId");

        // 不匹配则，直接跳过
        if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
            return;
        }

        // <3> 获得各种属性
        Integer fetchSize = context.getIntAttribute("fetchSize");
        Integer timeout = context.getIntAttribute("timeout");
        String parameterMap = context.getStringAttribute("parameterMap");
        String parameterType = context.getStringAttribute("parameterType");
        Class<?> parameterTypeClass = resolveClass(parameterType);
        String resultMap = context.getStringAttribute("resultMap");
        String resultType = context.getStringAttribute("resultType");
        String lang = context.getStringAttribute("lang");

        // <4> 获得 lang 对应的 LanguageDriver 对象，一般为 XMLLanguageDriver
        LanguageDriver langDriver = getLanguageDriver(lang);

        // <5> 获得 resultType 对应的类
        Class<?> resultTypeClass = resolveClass(resultType);

        // <6> 获得 resultSet 对应的枚举值，https://www.iteye.com/blog/jinguo-365373
        String resultSetType = context.getStringAttribute("resultSetType");

        // <7> 获得 statementType 对应的枚举值，默认是 预编译
        StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType",
                StatementType.PREPARED.toString()));


        ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);

        // <8> 获得 SQL 对应的 SqlCommandType 枚举值，即判断是 insert or  update 等
        String nodeName = context.getNode().getNodeName();
        SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));

        // <9> 获得各种属性
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
        // select 语句，默认不清空 cache
        boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);

        // select 语句，默认设置缓存
        boolean useCache = context.getBooleanAttribute("useCache", isSelect);

        // 结果有序，默认不排序
        boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

        // Include Fragments before parsing
        // <10> 创建 XMLIncludeTransformer 对象，并替换 <include /> 标签相关的内容
        XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
        includeParser.applyIncludes(context.getNode());

        // Parse selectKey after includes and remove them.
        // <11> 解析 <selectKey /> 标签
        processSelectKeyNodes(id, parameterTypeClass, langDriver);

        // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
        // <12> 创建 SqlSource
        SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);

        // <13> 获得 KeyGenerator 对象
        String resultSets = context.getStringAttribute("resultSets");
        String keyProperty = context.getStringAttribute("keyProperty");
        String keyColumn = context.getStringAttribute("keyColumn");
        KeyGenerator keyGenerator;

        // <13.1> 优先，从 configuration 中获得 KeyGenerator 对象。如果存在，意味着是 <selectKey /> 标签配置的
        String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
        keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
        if (configuration.hasKeyGenerator(keyStatementId)) {
            keyGenerator = configuration.getKeyGenerator(keyStatementId);
        } else {// <13.2> 其次，根据标签属性的情况，判断是否使用对应的 Jdbc3KeyGenerator 或者 NoKeyGenerator 对象
            keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
                    configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
                    ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        }

        // 创建 MappedStatement 对象
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
                fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
                resultSetTypeEnum, flushCache, useCache, resultOrdered,
                keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
    }

    private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
        // <1> 获得 <selectKey /> 节点们
        List<XNode> selectKeyNodes = context.evalNodes("selectKey");

        // <2> 执行解析 <selectKey /> 节点们
        if (configuration.getDatabaseId() != null) {
            parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
        }

        parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
        // <3> 移除 <selectKey /> 节点们
        removeSelectKeyNodes(selectKeyNodes);
    }

    /**
     * @param parentId             父节点id
     * @param list                 {@code <selectKey /> }节点列表
     * @param parameterTypeClass
     * @param langDriver
     * @param skRequiredDatabaseId
     */
    private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass,
                                     LanguageDriver langDriver, String skRequiredDatabaseId) {
        // <1> 遍历 <selectKey /> 节点们
        for (XNode nodeToHandle : list) {
            // <2> 获得完整 id ，格式为 `${id}!selectKey`
            String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;

            // <3> 获得 databaseId ， 判断 databaseId 是否匹配
            String databaseId = nodeToHandle.getStringAttribute("databaseId");
            if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
                // <4> 执行解析单个 <selectKey /> 节点
                parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
            }
        }
    }

    /**
     * 解析
     * <pre>{@code
     * <selectKey keyProperty="id" order="AFTER" resultType="java.lang.Integer">
     *    SELECT LAST_INSERT_ID()
     * </selectKey>
     * } </pre>
     */
    private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass,
                                    LanguageDriver langDriver, String databaseId) {
        // <1.1> 获得各种属性和对应的类
        String resultType = nodeToHandle.getStringAttribute("resultType");
        Class<?> resultTypeClass = resolveClass(resultType);
        StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType",
                StatementType.PREPARED.toString()));
        String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
        String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
        boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

        //defaults
        // <1.2> 创建 MappedStatement 需要用到的默认值
        boolean useCache = false;
        boolean resultOrdered = false;
        KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
        Integer fetchSize = null;
        Integer timeout = null;
        boolean flushCache = false;
        String parameterMap = null;
        String resultMap = null;
        ResultSetType resultSetTypeEnum = null;

        // <1.3> 创建 SqlSource 对象
        SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
        SqlCommandType sqlCommandType = SqlCommandType.SELECT;

        // <1.4> 创建 MappedStatement 对象
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
                fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
                resultSetTypeEnum, flushCache, useCache, resultOrdered,
                keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

        // <2.1> 获得 SelectKeyGenerator 的编号，格式为 `${namespace}.${id}`
        id = builderAssistant.applyCurrentNamespace(id, false);

        // <2.2> 获得 MappedStatement 对象
        MappedStatement keyStatement = configuration.getMappedStatement(id, false);

        // <2.3> 创建 SelectKeyGenerator 对象，并添加到 configuration 中
        configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
    }

    private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
        for (XNode nodeToHandle : selectKeyNodes) {
            nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        // 如果不匹配，则返回 false
        if (requiredDatabaseId != null) {
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            // 如果未设置 requiredDatabaseId ，但是 databaseId 存在，说明还是不匹配，则返回 false
            if (databaseId != null) {
                return false;
            }
            // skip this statement if there is a previous one with a not null databaseId
            // 判断是否已经存在
            id = builderAssistant.applyCurrentNamespace(id, false);
            if (this.configuration.hasStatement(id, false)) {
                MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
                // 若存在，则判断原有的 sqlFragment 是否 databaseId 为空。因为，当前 databaseId 为空，这样两者才能匹配。
                if (previous.getDatabaseId() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private LanguageDriver getLanguageDriver(String lang) {
        // 解析 lang 对应的类
        Class<? extends LanguageDriver> langClass = null;
        if (lang != null) {
            // 加载类
            langClass = resolveClass(lang);
        }
        // 获得 LanguageDriver 对象
        return builderAssistant.getLanguageDriver(langClass);
    }

}
