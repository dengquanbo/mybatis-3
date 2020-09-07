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
package org.apache.ibatis.builder;

import java.util.*;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {
    // 资源引用的地址
    private final String resource;

    // 当前 Mapper 命名空间
    private String currentNamespace;

    // 当前 Cache 对象
    private Cache currentCache;

    // 是否未解析成功 Cache 引用
    private boolean unresolvedCacheRef; // issue #676

    /**
     * @param configuration 配置
     * @param resource      资源文件
     */
    public MapperBuilderAssistant(Configuration configuration, String resource) {
        super(configuration);
        ErrorContext.instance().resource(resource);
        this.resource = resource;
    }

    public String getCurrentNamespace() {
        return currentNamespace;
    }

    /**
     * 一旦设置了，后续调用该方法，必须和之前设置的一样
     */
    public void setCurrentNamespace(String currentNamespace) {
        // 如果传入的 currentNamespace 参数为空，抛出 BuilderException 异常
        if (currentNamespace == null) {
            throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
        }
        // 如果当前已经设置，并且还和传入的不相等，抛出 BuilderException 异常
        if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
            throw new BuilderException(
                    "Wrong namespace. Expected '" + this.currentNamespace + "' but found '" + currentNamespace + "'.");
        }
        // 设置
        this.currentNamespace = currentNamespace;
    }

    /**
     * 生成唯一在的标识
     *
     * @param base        当前名
     * @param isReference 是否是需要添加基础的名字，如在mapper中的子节点都需要添加mapper的namespace
     */
    public String applyCurrentNamespace(String base, boolean isReference) {
        if (base == null) {
            return null;
        }
        if (isReference) {
            // is it qualified with any namespace yet?
            if (base.contains(".")) {
                return base;
            }
        } else {
            // is it qualified with this namespace yet?
            //
            if (base.startsWith(currentNamespace + ".")) {
                return base;
            }
            // 不能包含 .
            if (base.contains(".")) {
                throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
            }
        }
        // 拼接 currentNamespace + base
        return currentNamespace + "." + base;
    }

    /**
     * 获取{@code <cache-ref namespace=""/> }中namespace定义的cache，如果获取不到抛出未完成异常，最后统一再次获取
     */
    public Cache useCacheRef(String namespace) {
        // 可以看出<cache-ref>必须定义 namespace 属性
        if (namespace == null) {
            throw new BuilderException("cache-ref element requires a namespace attribute.");
        }
        try {
            unresolvedCacheRef = true;
            // 从 configuration#caches 的缓存中获取，获得不到，抛出 IncompleteElementException 异常
            Cache cache = configuration.getCache(namespace);
            if (cache == null) {
                throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
            }
            // 记录当前 Cache 对象
            currentCache = cache;
            // 标记不存在未处理的 cache
            unresolvedCacheRef = false;
            return cache;
        } catch (IllegalArgumentException e) {
            throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
        }
    }

    /**
     * 帮助创建Cache对象
     *
     * @param typeClass     缓存实现类
     * @param evictionClass 淘汰策略实现类
     * @param flushInterval 缓存刷新频率
     * @param size          缓存大小
     * @param readWrite     是否读写
     * @param blocking      是否阻塞
     * @param props         属性
     */
    public Cache useNewCache(Class<? extends Cache> typeClass, Class<? extends Cache> evictionClass, Long flushInterval,
                             Integer size, boolean readWrite, boolean blocking, Properties props) {
        // 创建 Cache 对象
        Cache cache = new CacheBuilder(currentNamespace).implementation(valueOrDefault(typeClass, PerpetualCache.class))
                .addDecorator(valueOrDefault(evictionClass, LruCache.class)).clearInterval(flushInterval).size(size)
                .readWrite(readWrite).blocking(blocking).properties(props).build();
        // 添加到 configuration 的 caches 中
        configuration.addCache(cache);
        // 赋值给 currentCache
        currentCache = cache;
        return cache;
    }

    public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
        id = applyCurrentNamespace(id, false);
        ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings)
                .build();
        configuration.addParameterMap(parameterMap);
        return parameterMap;
    }

    /**
     * 构造 ResultMapping 对象
     */
    public ParameterMapping buildParameterMapping(Class<?> parameterType, String property, Class<?> javaType,
                                                  JdbcType jdbcType, String resultMap, ParameterMode parameterMode,
                                                  Class<? extends TypeHandler<?>> typeHandler, Integer numericScale) {
        resultMap = applyCurrentNamespace(resultMap, true);

        // Class parameterType = parameterMapBuilder.type();
        // 解析对应的 Java Type 类和 TypeHandler 对象
        Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);
        // 创建 ResultMapping 对象
        return new ParameterMapping.Builder(configuration, property, javaTypeClass).jdbcType(jdbcType)
                .resultMapId(resultMap).mode(parameterMode).numericScale(numericScale).typeHandler(typeHandlerInstance)
                .build();
    }

    /**
     * 创建 ResultMap 对象，并添加到 Configuration 中
     */
    public ResultMap addResultMap(String id, Class<?> type, String extend, Discriminator discriminator,
                                  List<ResultMapping> resultMappings, Boolean autoMapping) {
        // 获得 ResultMap 编号，即格式为 `${namespace}.${id}` 。
        id = applyCurrentNamespace(id, false);
        // 获取完整的 extend 属性，即格式为 `${namespace}.${extend}` 。从这里的逻辑来看，貌似只能自己 namespace 下的
        // ResultMap 。
        extend = applyCurrentNamespace(extend, true);

        // 如果有父类，则将父类的 ResultMap 集合，添加到 resultMappings 中。
        if (extend != null) {
            // 获得 extend 对应的 ResultMap 对象。如果不存在，则抛出 IncompleteElementException 异常
            if (!configuration.hasResultMap(extend)) {
                throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
            }
            // 获取 extend 的 ResultMap 对象的 ResultMapping 集合，并移除 resultMappings
            ResultMap resultMap = configuration.getResultMap(extend);
            List<ResultMapping> extendedResultMappings = new ArrayList<>(resultMap.getResultMappings());
            extendedResultMappings.removeAll(resultMappings);
            // Remove parent constructor if this resultMap declares a constructor.
            // 判断当前的 resultMappings 是否有构造方法，如果有，则从 extendedResultMappings 移除所有的构造类型的
            // ResultMapping 们
            boolean declaresConstructor = false;
            for (ResultMapping resultMapping : resultMappings) {
                if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    declaresConstructor = true;
                    break;
                }
            }
            if (declaresConstructor) {
                Iterator<ResultMapping> extendedResultMappingsIter = extendedResultMappings.iterator();
                while (extendedResultMappingsIter.hasNext()) {
                    if (extendedResultMappingsIter.next().getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                        extendedResultMappingsIter.remove();
                    }
                }
            }
            // 将 extendedResultMappings 添加到 resultMappings 中
            resultMappings.addAll(extendedResultMappings);
        }
        // 创建 ResultMap 对象
        ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping)
                .discriminator(discriminator).build();
        // 添加到 configuration 中
        configuration.addResultMap(resultMap);
        return resultMap;
    }

    public Discriminator buildDiscriminator(Class<?> resultType, String column, Class<?> javaType, JdbcType jdbcType,
                                            Class<? extends TypeHandler<?>> typeHandler,
                                            Map<String, String> discriminatorMap) {
        // 构建 ResultMapping 对象
        ResultMapping resultMapping = buildResultMapping(resultType, null, column, javaType, jdbcType, null, null, null,
                null, typeHandler, new ArrayList<ResultFlag>(), null, null, false);
        // 创建 namespaceDiscriminatorMap 映射
        Map<String, String> namespaceDiscriminatorMap = new HashMap<>();
        for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
            String resultMap = e.getValue();
            resultMap = applyCurrentNamespace(resultMap, true);
            namespaceDiscriminatorMap.put(e.getKey(), resultMap);
        }
        // 构建 Discriminator 对象
        return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap).build();
    }

    /**
     * @param id             唯一id
     * @param sqlSource      sql语句对象
     * @param statementType
     * @param sqlCommandType 什么类型的sql
     * @param fetchSize
     * @param timeout
     * @param parameterMap
     * @param parameterType  参数类型
     * @param resultMap
     * @param resultType
     * @param resultSetType
     * @param flushCache
     * @param useCache
     * @param resultOrdered
     * @param keyGenerator
     * @param keyProperty
     * @param keyColumn
     * @param databaseId
     * @param lang           sql解析器
     * @param resultSets
     * @return
     */
    public MappedStatement addMappedStatement(String id, SqlSource sqlSource, StatementType statementType,
                                              SqlCommandType sqlCommandType, Integer fetchSize, Integer timeout,
                                              String parameterMap,
                                              Class<?> parameterType, String resultMap, Class<?> resultType,
                                              ResultSetType resultSetType,
                                              boolean flushCache, boolean useCache, boolean resultOrdered,
                                              KeyGenerator keyGenerator, String keyProperty,
                                              String keyColumn, String databaseId, LanguageDriver lang,
                                              String resultSets) {

        if (unresolvedCacheRef) {
            throw new IncompleteElementException("Cache-ref not yet resolved");
        }


        id = applyCurrentNamespace(id, false);
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

        MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource,
                sqlCommandType).resource(resource).fetchSize(fetchSize).timeout(timeout).statementType(statementType)
                .keyGenerator(keyGenerator).keyProperty(keyProperty).keyColumn(keyColumn).databaseId(databaseId)
                .lang(lang).resultOrdered(resultOrdered).resultSets(resultSets)
                .resultMaps(getStatementResultMaps(resultMap, resultType, id)).resultSetType(resultSetType)
                .flushCacheRequired(valueOrDefault(flushCache, !isSelect))
                .useCache(valueOrDefault(useCache, isSelect)).cache(currentCache);

        ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);
        if (statementParameterMap != null) {
            statementBuilder.parameterMap(statementParameterMap);
        }

        MappedStatement statement = statementBuilder.build();

        // 最后添加到 configuration 中
        configuration.addMappedStatement(statement);
        return statement;
    }

    private <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private ParameterMap getStatementParameterMap(String parameterMapName, Class<?> parameterTypeClass,
                                                  String statementId) {
        // 得到 id
        parameterMapName = applyCurrentNamespace(parameterMapName, true);
        ParameterMap parameterMap = null;
        if (parameterMapName != null) {
            try {
                // 从配置中获取
                parameterMap = configuration.getParameterMap(parameterMapName);
            } catch (IllegalArgumentException e) {
                throw new IncompleteElementException("Could not find parameter map " + parameterMapName, e);
            }
        } else if (parameterTypeClass != null) { // 从 parameterType获取
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            // id 为 cn.dqb.mybatisstudy.mapper.BlogMapper.delRoleProfile-Inline
            parameterMap = new ParameterMap.Builder(configuration, statementId + "-Inline", parameterTypeClass,
                    parameterMappings).build();
        }
        return parameterMap;
    }

    private List<ResultMap> getStatementResultMaps(String resultMap, Class<?> resultType, String statementId) {
        resultMap = applyCurrentNamespace(resultMap, true);

        List<ResultMap> resultMaps = new ArrayList<>();
        if (resultMap != null) {
            String[] resultMapNames = resultMap.split(",");
            for (String resultMapName : resultMapNames) {
                try {
                    resultMaps.add(configuration.getResultMap(resultMapName.trim()));
                } catch (IllegalArgumentException e) {
                    throw new IncompleteElementException("Could not find result map " + resultMapName, e);
                }
            }
        } else if (resultType != null) {
            ResultMap inlineResultMap = new ResultMap.Builder(configuration, statementId + "-Inline", resultType,
                    new ArrayList<ResultMapping>(), null).build();
            resultMaps.add(inlineResultMap);
        }
        return resultMaps;
    }

    public ResultMapping buildResultMapping(Class<?> resultType, String property, String column, Class<?> javaType,
                                            JdbcType jdbcType, String nestedSelect, String nestedResultMap,
                                            String notNullColumn, String columnPrefix,
                                            Class<? extends TypeHandler<?>> typeHandler, List<ResultFlag> flags,
                                            String resultSet, String foreignColumn,
                                            boolean lazy) {
        // 解析对应的 Java Type 类和 TypeHandler 对象
        Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

        // 解析组合字段名称成 ResultMapping 集合。涉及「关联的嵌套查询」。联合主键 {property=column,property=column}
        List<ResultMapping> composites = parseCompositeColumnName(column);

        // 创建 ResultMapping 对象
        return new ResultMapping.Builder(configuration, property, column, javaTypeClass).jdbcType(jdbcType)
                .nestedQueryId(applyCurrentNamespace(nestedSelect, true))
                .nestedResultMapId(applyCurrentNamespace(nestedResultMap, true)).resultSet(resultSet)
                .typeHandler(typeHandlerInstance).flags(flags == null ? new ArrayList<ResultFlag>() : flags)
                .composites(composites).notNullColumns(parseMultipleColumnNames(notNullColumn))
                .columnPrefix(columnPrefix).foreignColumn(foreignColumn).lazy(lazy).build();
    }

    /**
     * 多个column如{column1,column2} 将字符串解析成集合
     */
    private Set<String> parseMultipleColumnNames(String columnName) {
        Set<String> columns = new HashSet<>();
        if (columnName != null) {
            // 多个字段，使用 ，分隔
            if (columnName.indexOf(',') > -1) {
                StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
                while (parser.hasMoreTokens()) {
                    String column = parser.nextToken();
                    columns.add(column);
                }
            } else {
                columns.add(columnName);
            }
        }
        return columns;
    }

    private List<ResultMapping> parseCompositeColumnName(String columnName) {
        List<ResultMapping> composites = new ArrayList<>();
        if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
            // 分词，解析其中的 property 和 column 的组合对
            StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
            while (parser.hasMoreTokens()) {
                String property = parser.nextToken();
                String column = parser.nextToken();
                // 创建 ResultMapping 对象
                ResultMapping complexResultMapping = new ResultMapping.Builder(configuration, property, column,
                        configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
                // 添加到 composites 中
                composites.add(complexResultMapping);
            }
        }
        return composites;
    }

    private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {
        if (javaType == null && property != null) {
            try {
                MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
                javaType = metaResultType.getSetterType(property);
            } catch (Exception e) {
                // ignore, following null check statement will deal with the situation
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

    private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType,
                                              JdbcType jdbcType) {
        if (javaType == null) {
            if (JdbcType.CURSOR.equals(jdbcType)) {
                javaType = java.sql.ResultSet.class;
            } else if (Map.class.isAssignableFrom(resultType)) {
                javaType = Object.class;
            } else {
                MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
                javaType = metaResultType.getGetterType(property);
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

    /**
     * Backward compatibility signature
     */
    public ResultMapping buildResultMapping(Class<?> resultType, String property, String column, Class<?> javaType,
                                            JdbcType jdbcType, String nestedSelect, String nestedResultMap,
                                            String notNullColumn, String columnPrefix,
                                            Class<? extends TypeHandler<?>> typeHandler, List<ResultFlag> flags) {
        return buildResultMapping(resultType, property, column, javaType, jdbcType, nestedSelect, nestedResultMap,
                notNullColumn, columnPrefix, typeHandler, flags, null, null, configuration.isLazyLoadingEnabled());
    }

    public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
        // 获得 langClass 类
        if (langClass != null) {
            configuration.getLanguageRegistry().register(langClass);
        } else {// 如果为空，则使用默认类
            langClass = configuration.getLanguageRegistry().getDefaultDriverClass();
        }
        // 获得 LanguageDriver 对象
        return configuration.getLanguageRegistry().getDriver(langClass);
    }

    /**
     * Backward compatibility signature
     */
    public MappedStatement addMappedStatement(String id, SqlSource sqlSource, StatementType statementType,
                                              SqlCommandType sqlCommandType, Integer fetchSize, Integer timeout,
                                              String parameterMap,
                                              Class<?> parameterType, String resultMap, Class<?> resultType,
                                              ResultSetType resultSetType,
                                              boolean flushCache, boolean useCache, boolean resultOrdered,
                                              KeyGenerator keyGenerator, String keyProperty,
                                              String keyColumn, String databaseId, LanguageDriver lang) {
        return addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout, parameterMap,
                parameterType, resultMap, resultType, resultSetType, flushCache, useCache, resultOrdered, keyGenerator,
                keyProperty, keyColumn, databaseId, lang, null);
    }

}
