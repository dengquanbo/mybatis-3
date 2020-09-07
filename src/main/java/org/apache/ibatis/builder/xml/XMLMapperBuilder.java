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

import java.io.InputStream;
import java.io.Reader;
import java.util.*;

import org.apache.ibatis.builder.*;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * Mapper.xml 配置构建器，主要负责解析 Mapper 映射配置文件。
 *
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

    /**
     * 基于 Java XPath 解析器
     */
    private final XPathParser parser;

    /**
     * Mapper 构造器助手
     */
    private final MapperBuilderAssistant builderAssistant;

    /**
     * 可被其他语句引用的可重用语句块的集合
     * <p>
     * 例如：{@code <sql id="userColumns"> ${alias}.id,${alias}.username,${alias}.password </sql> }
     */
    private final Map<String, XNode> sqlFragments;

    /**
     * 资源引用的地址
     */
    private final String resource;

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource,
                            Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource,
                            Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()), configuration,
                resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource,
                            Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource,
                            Map<String, XNode> sqlFragments) {
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource,
                             Map<String, XNode> sqlFragments) {
        super(configuration);

        // 创建 MapperBuilderAssistant 对象
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);

        this.parser = parser;

        this.sqlFragments = sqlFragments;

        // 文件资源
        this.resource = resource;
    }

    public void parse() {
        // 判断当前 Mapper 是否已经加载过
        if (!configuration.isResourceLoaded(resource)) {

            // 解析 <mapper /> 节点及其子节点
            configurationElement(parser.evalNode("/mapper"));

            // 标记该 Mapper 已经加载过
            configuration.addLoadedResource(resource);

            // 绑定 mapper
            bindMapperForNamespace();
        }
        // 解析待定的 <resultMap /> 节点
        parsePendingResultMaps();

        // 解析待定的 <cache-ref /> 节点
        parsePendingCacheRefs();

        // 解析待定的 SQL 语句的节点
        parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    /**
     * 解析{@code <mapper /> }中的属性和子节点
     * <pre>{@code
     *  <mapper namespace="NewsMapper">
     *      <cache-ref />
     *      <cache />
     *      <resultMap />
     *      <sql />
     *      <select />
     *      <insert />
     *      <update />
     *      <delete />
     *  </mapper>
     * }</pre>
     */
    private void configurationElement(XNode context) {
        try {
            // 获得属性值 namespace
            String namespace = context.getStringAttribute("namespace");

            // 这里可以看出每个 mapper.xml 文件必须设置 namespace 不能为空
            if (namespace == null || namespace.equals("")) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }

            // 设置 namespace 属性
            builderAssistant.setCurrentNamespace(namespace);

            // 解析 <cache-ref /> 节点，只会解析第一个，所以多个不会报错，但也不会生效
            cacheRefElement(context.evalNode("cache-ref"));

            // 解析 <cache /> 节点
            cacheElement(context.evalNode("cache"));

            // 已废弃！老式风格的参数映射。内联参数是首选,这个元素可能在将来被移除，这里不会记录。
            parameterMapElement(context.evalNodes("/mapper/parameterMap"));

            // 解析 <resultMap /> 节点们
            resultMapElements(context.evalNodes("/mapper/resultMap"));

            // 解析 <sql /> 节点们
            sqlElement(context.evalNodes("/mapper/sql"));

            // 解析 <select /> <insert /> <update /> <delete /> 节点们
            buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e,
                    e);
        }
    }

    /**
     * 解析{@code <select />、<insert />、<update />、<delete /> }节点们
     */
    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            buildStatementFromContext(list, configuration.getDatabaseId());
        }
        buildStatementFromContext(list, null);
    }

    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        // 遍历 <select /> <insert /> <update /> <delete /> 节点们
        for (XNode context : list) {

            // 创建 XMLStatementBuilder 对象，执行解析
            final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant,
                    context, requiredDatabaseId);
            try {
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                // 解析失败，添加到 configuration 中
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        // 获得 ResultMapResolver 集合，并遍历进行处理
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    // 执行解析
                    iter.next().resolve();
                    // 成功移除
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                    // 解析失败，不抛出异常，等待下一次解析
                }
            }
        }
    }

    private void parsePendingCacheRefs() {
        // 获得 CacheRefResolver 集合，并遍历进行处理
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            while (iter.hasNext()) {
                try {
                    // 执行解析
                    iter.next().resolveCacheRef();

                    // 移除
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                }
            }
        }
    }

    private void parsePendingStatements() {
        // 获得 XMLStatementBuilder 集合，并遍历进行处理
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        synchronized (incompleteStatements) {
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    // 执行解析
                    iter.next().parseStatementNode();

                    // 移除
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                }
            }
        }
    }

    /**
     * 解析{@code <cache-ref /> }节点
     *
     * <pre>{@code
     * <cache-ref namespace="" />
     * } </pre>
     */
    private void cacheRefElement(XNode context) {
        if (context != null) {
            // 获得指向的 namespace 名字，并添加到 configuration 的 cacheRefMap 中
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));

            // 创建 CacheRefResolver 对象，并执行解析
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant,
                    context.getStringAttribute("namespace"));
            try {
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                // 解析失败，添加到 configuration 的 incompleteCacheRefs 中
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    /**
     * 解析{@code <cache /> }标签
     *
     * <pre>{@code
     *     // 使用默认缓存
     *   <cache eviction="FIFO" flushInterval="60000"  size="512" readOnly="true"/>
     *    // 使用自定义缓存
     *    <cache type="com.domain.something.MyCustomCache">
     *        <property name="cacheFile" value="/tmp/my-custom-cache.tmp"/>
     *    </cache>
     * }</pre>
     */
    private void cacheElement(XNode context) throws Exception {
        if (context != null) {
            // 获得负责存储的 Cache 实现类，如果type未配置默认为 PERPETUAL
            /**
             * 默认为 {@link org.apache.ibatis.cache.impl.PerpetualCache}，由 {@link Configuration}默认添加
             * */
            String type = context.getStringAttribute("type", "PERPETUAL");

            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);

            // 获得负责过期的 Cache 实现类，默认lru
            String eviction = context.getStringAttribute("eviction", "LRU");

            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);

            // 获得 flushInterval、size、readWrite、blocking 属性
            Long flushInterval = context.getLongAttribute("flushInterval");

            // 引用数目
            Integer size = context.getIntAttribute("size");

            // 默认读写
            boolean readWrite = !context.getBooleanAttribute("readOnly", false);

            // 默认不阻塞
            boolean blocking = context.getBooleanAttribute("blocking", false);

            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();

            // 创建 Cache 对象
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    /**
     *
     */
    private void parameterMapElement(List<XNode> list) throws Exception {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                @SuppressWarnings("unchecked")
                Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(
                        typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property,
                        javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    /**
     * 解析<resultMap/> 集合
     */
    private void resultMapElements(List<XNode> list) throws Exception {
        for (XNode resultMapNode : list) {
            try {
                // 实际分别解析
                resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
        return resultMapElement(resultMapNode, Collections.<ResultMapping>emptyList(), null);
    }

    /**
     * 解析{@code <resultMap />} 节点
     *
     * <pre>{@code
     * <resultMap id="NewsListMap" type="cn.cover.server.news.domain.News" extends="" autoMapping="">
     *
     *   <id column="id" property="id" jdbcType="INTEGER"/>
     *   <result column="creator" property="creator" jdbcType="INTEGER"/>
     *
     * </resultMap>
     * </pre>}
     */
    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings,
                                       Class<?> enclosingType) throws Exception {
        // 利用threadlocal记录信息
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());

        // 获得 id 属性
        String id = resultMapNode.getStringAttribute("id", resultMapNode.getValueBasedIdentifier());

        // 获得 type 属性
        String type = resultMapNode.getStringAttribute("type", resultMapNode.getStringAttribute("ofType",
                resultMapNode.getStringAttribute("resultType", resultMapNode.getStringAttribute("javaType"))));

        // 获得 extends 属性
        String extend = resultMapNode.getStringAttribute("extends");

        // 获得 autoMapping 属性
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");

        // 解析 type 对应的类
        Class<?> typeClass = resolveClass(type);
        if (typeClass == null) {
            typeClass = inheritEnclosingType(resultMapNode, enclosingType);
        }
        Discriminator discriminator = null;

        // 创建 ResultMapping 集合
        List<ResultMapping> resultMappings = new ArrayList<>();
        resultMappings.addAll(additionalResultMappings);

        // 遍历 <resultMap /> 的子节点
        List<XNode> resultChildren = resultMapNode.getChildren();
        for (XNode resultChild : resultChildren) {
            // 处理 <constructor /> 节点
            if ("constructor".equals(resultChild.getName())) {
                processConstructorElement(resultChild, typeClass, resultMappings);
            }
            // 处理 <discriminator /> 节点
            else if ("discriminator".equals(resultChild.getName())) {
                discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
            }

            // 处理其它节点
            else {
                List<ResultFlag> flags = new ArrayList<>();
                if ("id".equals(resultChild.getName())) {
                    flags.add(ResultFlag.ID);
                }
                resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        // 创建 ResultMapResolver 对象，执行解析
        ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend,
                discriminator, resultMappings, autoMapping);
        try {
            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            // 解析失败，添加到 configuration 中
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
        if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            String property = resultMapNode.getStringAttribute("property");
            if (property != null && enclosingType != null) {
                MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
                return metaResultType.getSetterType(property);
            }
        } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            return enclosingType;
        }
        return null;
    }

    /**
     * 解析{@code <constructor />}
     * <pre>{@code
     *  <constructor>
     *      <idArg jdbcType="" javaType="" typeHandler="" column="" resultMap= select=""/>
     *      <arg jdbcType="" javaType="" typeHandler="" column="" resultMap= select=""/>
     *  </constructor>
     * }</pre>
     */
    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings)
            throws Exception {
        // 遍历 <constructor /> 的子节点列表
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            // 获得 ResultFlag 集合
            List<ResultFlag> flags = new ArrayList<>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                flags.add(ResultFlag.ID);
            }
            // 将当前子节点构建成 ResultMapping 对象，并添加到 resultMappings 中
            resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    /**
     * 解析{@code <discriminator/>}
     * <pre>{@code
     * <discriminator javaType="" column="" typeHandler="" jdbcType="">
     *      <case value="" resultMap="" resultType=""></case>
     *      <case value="" resultMap="" resultType=""></case>
     * </discriminator>
     * }</pre>
     */
    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType,
                                                      List<ResultMapping> resultMappings) throws Exception {
        // 解析javaType、column、typeHandler、jdbcType
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        // 解析 javaType 类
        Class<?> javaTypeClass = resolveClass(javaType);
        // 解析 typeHandler 类
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        // 解析 jdbcType 类
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);

        // 遍历 <discriminator /> 的子节点，解析成 discriminatorMap 集合
        Map<String, String> discriminatorMap = new HashMap<>();
        for (XNode caseChild : context.getChildren()) {
            // 获得属性 value
            String value = caseChild.getStringAttribute("value");
            // 获得属性 resultMap
            String resultMap = caseChild.getStringAttribute("resultMap",
                    processNestedResultMappings(caseChild, resultMappings, resultType));
            discriminatorMap.put(value, resultMap);
        }
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass,
                discriminatorMap);
    }

    /**
     * 解析所有{@code <sql /> }节点
     * <pre>{@code
     * <sql id="Base_News_Column_List" databaseId="" lang="">
     * }<pre/>
     */
    private void sqlElement(List<XNode> list) throws Exception {
        if (configuration.getDatabaseId() != null) {
            sqlElement(list, configuration.getDatabaseId());
        }
        sqlElement(list, null);
    }

    private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
        // 遍历所有 <sql /> 节点
        for (XNode context : list) {
            // 获得 databaseId 属性
            String databaseId = context.getStringAttribute("databaseId");

            // 获得完整的 id 属性，格式为 `${namespace}.${id}`
            String id = context.getStringAttribute("id");

            id = builderAssistant.applyCurrentNamespace(id, false);

            // 判断 databaseId 是否匹配
            if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                // 添加到 sqlFragments 中
                sqlFragments.put(id, context);
            }
        }
    }

    /**
     * @param id                 命名id
     * @param databaseId         当前的 databaseId
     * @param requiredDatabaseId 需要的 databaseId
     * @return true：符合 false:不满足
     */
    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        // configuration 定义的 databaseId 与 sql 定义的 databaseId 不相同。则不匹配
        if (requiredDatabaseId != null) {
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            // 如果未设置 requiredDatabaseId ，但是 databaseId 存在，说明还是不匹配，则返回 false
            if (databaseId != null) {
                return false;
            }
            // 判断是否已经存在
            // skip this fragment if there is a previous one with a not null databaseId
            if (this.sqlFragments.containsKey(id)) {
                // 若存在，则判断原有的 sqlFragment 是否 databaseId 为空。因为，当前 databaseId 为空，这样两者才能匹配。
                XNode context = this.sqlFragments.get(id);
                if (context.getStringAttribute("databaseId") != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 将当前节点构建成 ResultMapping 对象
     */
    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags)
            throws Exception {
        // 获得属性名
        String property;
        if (flags.contains(ResultFlag.CONSTRUCTOR)) {
            // 如果有构造方法，则从 name 属性中获取
            property = context.getStringAttribute("name");
        } else {
            // 否则从，property 获取
            property = context.getStringAttribute("property");
        }
        // 数据库对应列
        String column = context.getStringAttribute("column");

        // java类型
        String javaType = context.getStringAttribute("javaType");

        // jdbc 类型
        String jdbcType = context.getStringAttribute("jdbcType");

        // select 属性
        String nestedSelect = context.getStringAttribute("select");

        // 这里会解析 <association> <collection> <case> 内嵌的resultMap熟悉
        String nestedResultMap = context.getStringAttribute("resultMap",
                processNestedResultMappings(context, Collections.<ResultMapping>emptyList(), resultType));


        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");

        // 判断是否来加载，先从 fetchType 拿，为空则根据系统配置拿(默认为 false)
        boolean lazy = "lazy".equals(
                context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
        // 获得各种属性对应的类
        Class<?> javaTypeClass = resolveClass(javaType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);

        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum,
                nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet,
                foreignColumn, lazy);
    }

    /**
     * 解析嵌套的resultmap属性
     */
    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings,
                                               Class<?> enclosingType) throws Exception {
        // 处理 <association/> <collection/> <case />标签
        if ("association".equals(context.getName()) || "collection".equals(context.getName())
                || "case".equals(context.getName())) {
            if (context.getStringAttribute("select") == null) {
                // 检验<collection>节点
                validateCollection(context, enclosingType);
                // 解析，并返回 ResultMap
                ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
                return resultMap.getId();
            }
        }
        return null;
    }

    /**
     * 检验<collection />
     * <p>
     * resultMap 与 resultType 其中有一个不为空，说明配置争取
     * <p>
     * 如果Collection的resultMap，resultType都为空，那么enclosingType 中必须有property属性配置的setXXX 方法，否则抛出 BuilderException
     */
    protected void validateCollection(XNode context, Class<?> enclosingType) {
        if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
                && context.getStringAttribute("resultType") == null) {
            MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
            String property = context.getStringAttribute("property");
            if (!metaResultType.hasSetter(property)) {
                throw new BuilderException("Ambiguous collection type for property '" + property
                        + "'. You must specify 'resultType' or 'resultMap'.");
            }
        }
    }

    /**
     * 绑定 Mapper
     */
    private void bindMapperForNamespace() {
        // <1> 获得 Mapper 映射配置文件对应的 Mapper 接口，实际上类名就是 namespace
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                // 尝试获得 namespace，对应的 Mapper 接口，不存在不影响
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                // ignore, bound type is not required
            }
            if (boundType != null) {
                // 如果在类路径找到了该 Mapper 接口，则进行添加
                if (!configuration.hasMapper(boundType)) {
                    // Spring may not know the real resource name so we set a flag
                    // to prevent loading again this resource from the mapper interface
                    // look at MapperAnnotationBuilder#loadXmlResource

                    // 标记 namespace 已经添加，避免 MapperAnnotationBuilder#loadXmlResource(...) 重复加载
                    // 如果是从 mapper.xml文件，找到的 mapper 则，资源为 "namespace:" + namespace
                    configuration.addLoadedResource("namespace:" + namespace);

                    // 后续就是 Mapper.class 文件的解析了
                    configuration.addMapper(boundType);
                }
            }
        }
    }

}
