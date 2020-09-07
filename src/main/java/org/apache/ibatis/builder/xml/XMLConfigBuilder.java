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
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * 实际解析 mybaits-config.xml 工具类
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {
    /**
     * 封装后的 XPath 解析器
     */
    private final XPathParser parser;

    // ReflectorFactory 对象
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    // 是否已解析
    private boolean parsed;

    // 环境
    private String environment;

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    /**
     * 最终调用该构造方法
     */
    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        // 新建一个 Configuration 对象
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");

        // 设置 Configuration 的 variables 属性
        this.configuration.setVariables(props);

        // 初始为未解析过
        this.parsed = false;

        // 设置环境
        this.environment = environment;

        // 设置解析器
        this.parser = parser;
    }

    /**
     * 解析 mybatis-config.xml 文件
     */
    public Configuration parse() {
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        // 标记已解析
        parsed = true;
        // 从XML 的根节点 <configuration> ，开始解析
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    /**
     * 解析 XNode 信息
     */
    private void parseConfiguration(XNode root) {
        try {
            // issue #117 read properties first
            // 解析 <properties /> 标签
            propertiesElement(root.evalNode("properties"));

            // 解析 <settings /> 标签
            Properties settings = settingsAsProperties(root.evalNode("settings"));

            // 加载自定义 VFS 实现类
            loadCustomVfs(settings);

            // 解析 <typeAliases /> 标签
            typeAliasesElement(root.evalNode("typeAliases"));

            // 解析 <plugins /> 标签
            pluginElement(root.evalNode("plugins"));

            // 解析 <objectFactory /> 标签
            objectFactoryElement(root.evalNode("objectFactory"));

            // 解析 <objectWrapperFactory /> 标签
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));

            // 解析 <reflectorFactory /> 标签
            reflectorFactoryElement(root.evalNode("reflectorFactory"));

            // 赋值 <settings /> 到 Configuration 属性
            settingsElement(settings);
            // read it after objectFactory and objectWrapperFactory issue #631
            // 解析 <environments /> 标签
            environmentsElement(root.evalNode("environments"));

            // 解析 <databaseIdProvider /> 标签
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));

            // 解析 <typeHandlers /> 标签
            typeHandlerElement(root.evalNode("typeHandlers"));

            // 解析 <mappers /> 标签
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    private Properties settingsAsProperties(XNode context) {
        if (context == null) {
            return new Properties();
        }
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class
        // 校验每个属性，在 Configuration 中，有相应的 setting 方法，否则抛出 BuilderException 异常
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException(
                        "The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    /**
     * 解析 vfsImpl
     */
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        // 获得 vfsImpl 属性
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            // 使用 , 作为分隔符，拆成 VFS 类名的数组
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    // 获得 VFS 类
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    // 设置到 Configuration 中
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            // 遍历节点 <typeAliases> 下的子节点 <typeAlias>
            for (XNode child : parent.getChildren()) {
                // 指定为包的情况下，注册包下的每个类
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                }
                // 指定为类的情况下，直接注册类和别名
                else {
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        // 获得类是否存在，注册到 typeAliasRegistry 中
                        Class<?> clazz = Resources.classForName(type);
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        // 若类不存在，则抛出 BuilderException 异常
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * 解析 <plugins> 下 <plugin> 标签
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 创建 Interceptor 对象，并设置属性
                String interceptor = child.getStringAttribute("interceptor");
                Properties properties = child.getChildrenAsProperties();
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                interceptorInstance.setProperties(properties);
                // 添加到 configuration 中
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    /**
     * 解析<objectFactory /> 节点
     */
    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ObjectFactory 的实现类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties properties = context.getChildrenAsProperties();
            // 创建 ObjectFactory 对象，并设置 Properties 属性
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            factory.setProperties(properties);
            // 设置 Configuration 的 objectFactory 属性
            configuration.setObjectFactory(factory);
        }
    }

    /**
     * 解析 <objectWrapperFactory />
     */
    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ObjectFactory 的实现类
            String type = context.getStringAttribute("type");
            // 创建 ObjectWrapperFactory 对象
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            // 设置 Configuration 的 objectWrapperFactory 属性
            configuration.setObjectWrapperFactory(factory);
        }
    }

    /**
     * 解析 <reflectorFactory />
     */
    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ReflectorFactory 的实现类
            String type = context.getStringAttribute("type");
            // 创建 ReflectorFactory 对象
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            // 设置 Configuration 的 reflectorFactory 属性
            configuration.setReflectorFactory(factory);
        }
    }

    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            // 读取子标签们，为 Properties 对象
            Properties defaults = context.getChildrenAsProperties();
            // 读取 <properties resource="" url=""> 标签上的 resource 与 url 标签
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");
            // resource 和 url 都存在的情况下，抛出 BuilderException 异常
            if (resource != null && url != null) {
                throw new BuilderException(
                        "The properties element cannot specify both a URL and a resource based property file " +
                                "reference.  Please specify one or the other.");
            }
            // 读取本地 Properties 配置文件到 defaults 中
            if (resource != null) {
                defaults.putAll(Resources.getResourceAsProperties(resource));
            }
            // 读取远程 Properties 配置文件到 defaults 中。
            else if (url != null) {
                defaults.putAll(Resources.getUrlAsProperties(url));
            }

            // 覆盖 configuration 中的 Properties 对象到 defaults 中。
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }

            // 设置 defaults 到 parser 和 configuration 中。
            parser.setVariables(defaults);
            configuration.setVariables(defaults);
        }
    }

    /**
     * 赋值 <settings /> 到 Configuration 属性
     */
    private void settingsElement(Properties props) throws Exception {
        configuration.setAutoMappingBehavior(
                AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior
                .valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration
                .setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(
                stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>) resolveClass(
                props.getProperty("defaultEnumTypeHandler"));
        configuration.setDefaultEnumTypeHandler(typeHandler);
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration
                .setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        // 设置日志前缀
        configuration.setLogPrefix(props.getProperty("logPrefix"));

        // 设置全局日志
        @SuppressWarnings("unchecked")
        Class<? extends Log> logImpl = (Class<? extends Log>) resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * 解析 <environments /> 标签
     *
     * <pre>
     *     <environments default="">
     *         <environment id="">
     *             <transactionManager type="">
     *                 <property name="" value=""/>
     *             </transactionManager>
     *             <dataSource type="">
     *                 <property name="" value=""/>
     *             </dataSource>
     *         </environment>
     *     </environments>
     * </pre>
     */
    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            // environment 属性为空，从 default 属性获得
            if (environment == null) {
                environment = context.getStringAttribute("default");
            }
            // 遍历 <environment id=""> 子节点
            for (XNode child : context.getChildren()) {
                String id = child.getStringAttribute("id");

                // 只设置 <environments default=""> <environment id = ""> default = id 的节点
                if (isSpecifiedEnvironment(id)) {
                    // 解析 `<transactionManager />` 标签，返回 TransactionFactory 对象
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));

                    // 解析 `<dataSource />` 标签，返回 DataSourceFactory 对象
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    DataSource dataSource = dsFactory.getDataSource();

                    // 创建 Environment.Builder 对象
                    Environment.Builder environmentBuilder = new Environment.Builder(id).transactionFactory(txFactory)
                            .dataSource(dataSource);
                    // 构造 Environment 对象，并设置到 configuration 中
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    /**
     * 解析 <databaseIdProvider /> 标签
     *
     * <pre>
     * <databaseIdProvider type="">
     *     <property name="" value=""/>
     * </databaseIdProvider>
     * </pre>
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            // 获得 DatabaseIdProvider 的类
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // 获得 Properties 对象
            Properties properties = context.getChildrenAsProperties();
            // 创建 DatabaseIdProvider 对象，并设置对应的属性
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            // 获得对应的 databaseId 编号
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            // 设置到 configuration 中
            configuration.setDatabaseId(databaseId);
        }
    }

    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 TransactionFactory 的类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();

            // 创建 TransactionFactory 对象，并设置属性
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 DataSourceFactory 的类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();

            // 创建 DataSourceFactory 对象，并设置属性
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     * 解析 <typeHandlers /> 标签
     *
     * <pre>
     *
     *  <typeHandlers>
     *     <package name="" />
     *     <typeHandler handler="" javaType="" jdbcType=""/>
     * </typeHandlers>
     *
     *
     * </pre>
     */
    private void typeHandlerElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 如果是 package 标签，则扫描该包
                if ("package".equals(child.getName())) {
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                }
                // 如果是 typeHandler 标签，则注册该 typeHandler 信息
                else {
                    // 获得 javaType、jdbcType、handler
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);
                    // 注册 typeHandler
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    /**
     * 解析{@code <mappers /> }标签
     *
     * <pre>
     * {@code
     * <mappers>
     *     <!-- 使用相对于类路径的资源引用 -->
     *     <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
     *
     *     <!-- 使用完全限定资源定位符（URL） -->
     *     <mapper url="file:///var/mappers/AuthorMapper.xml"/>
     *
     *     <!-- 使用映射器接口实现类的完全限定类名 -->
     *     <mapper class="org.mybatis.builder.AuthorMapper"/>
     *
     *     <!-- 将包内的映射器接口实现全部注册为映射器 -->
     *     <package name="org.mybatis.builder"/>
     * </mappers>
     * }</pre>
     */
    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 如果是 package 标签，则扫描该包
                if ("package".equals(child.getName())) {
                    // 获得包名
                    String mapperPackage = child.getStringAttribute("name");
                    // 添加到 configuration 中
                    configuration.addMappers(mapperPackage);
                } else {
                    // 获得 resource、url、class 属性
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    // 使用相对于类路径的资源引用
                    if (resource != null && url == null && mapperClass == null) {
                        ErrorContext.instance().resource(resource);
                        // 获得 resource 的 InputStream 对象
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        // 会为每一个 xml 文件，创建 XMLMapperBuilder 对象
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource,
                                configuration.getSqlFragments());

                        // 执行解析
                        mapperParser.parse();
                    }
                    // 使用完全限定资源定位符（URL）
                    else if (resource == null && url != null && mapperClass == null) {
                        ErrorContext.instance().resource(url);
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url,
                                configuration.getSqlFragments());
                        mapperParser.parse();
                    }
                    // 使用映射器接口实现类的完全限定类名
                    else if (resource == null && url == null && mapperClass != null) {
                        // 获得 Mapper 接口
                        Class<?> mapperInterface = Resources.classForName(mapperClass);

                        // 解析，添加到 configuration 中
                        configuration.addMapper(mapperInterface);
                    }
                    // 不能同时设置 url、resource、class 三个属性，否则抛出 BuilderException
                    else {
                        throw new BuilderException(
                                "A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
