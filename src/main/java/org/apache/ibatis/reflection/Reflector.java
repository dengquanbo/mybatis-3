/**
 * Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that allows for easy mapping between property
 * names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

    /**
     * 对应的类
     */
    private final Class<?> type;

    /**
     * 可读属性集合
     */
    private final String[] readablePropertyNames;

    /**
     * 可写属性集合
     */
    private final String[] writeablePropertyNames;

    /**
     * 属性对应的 setting 方法的映射。
     * <p>
     * key 为属性名称 value 为 Invoker 对象
     */
    private final Map<String, Invoker> setMethods = new HashMap<>();

    /**
     * 属性对应的 getting 方法的映射。
     * <p>
     * key 为属性名称 value 为 Invoker 对象
     */
    private final Map<String, Invoker> getMethods = new HashMap<>();

    /**
     * 属性对应的 setting 方法的方法参数类型的映射。{@link #setMethods}
     * <p>
     * key 为属性名称 value 为方法参数类型
     */
    private final Map<String, Class<?>> setTypes = new HashMap<>();

    /**
     * 属性对应的 getting 方法的返回值类型的映射。{@link #getMethods}
     * <p>
     * key 为属性名称 value 为返回值的类型
     */
    private final Map<String, Class<?>> getTypes = new HashMap<>();

    /**
     * 默认构造方法
     */
    private Constructor<?> defaultConstructor;

    /**
     * 不区分大小写的属性集合
     */
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

    /**
     * 构造方法传入一个类
     */
    public Reflector(Class<?> clazz) {
        // 设置对应的类
        type = clazz;

        // <1> 初始化 defaultConstructor
        addDefaultConstructor(clazz);

        // <2> 初始化 getMethods 和 getTypes ，通过遍历 getting 方法
        addGetMethods(clazz);

        // <3> 初始化 setMethods 和 setTypes ，通过遍历 setting 方法。
        addSetMethods(clazz);

        // <4> 初始化 getMethods + getTypes 和 setMethods + setTypes ，通过遍历 fields 属性。
        addFields(clazz);

        // <5> 初始化 readablePropertyNames、writeablePropertyNames、caseInsensitivePropertyMap 属性
        readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
        writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
        for (String propName : readablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
        for (String propName : writeablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }

    /**
     * Checks whether can control member accessible.
     *
     * @return If can control member accessible, it return {@literal true}
     * @since 3.5.0
     */
    public static boolean canControlMemberAccessible() {
        try {
            SecurityManager securityManager = System.getSecurityManager();
            if (null != securityManager) {
                securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
            }
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    private void addDefaultConstructor(Class<?> clazz) {
        // 反射，获取所有构造方法
        Constructor<?>[] consts = clazz.getDeclaredConstructors();
        for (Constructor<?> constructor : consts) {
            // 参数为 0，说明有无参构造方法
            if (constructor.getParameterTypes().length == 0) {
                this.defaultConstructor = constructor;
            }
        }
    }

    private void addGetMethods(Class<?> cls) {
        // 属性与其 getting 方法的映射
        Map<String, List<Method>> conflictingGetters = new HashMap<>();

        // 获得所有方法
        Method[] methods = getClassMethods(cls);
        for (Method method : methods) {

            // 参数大于 0，说明不可能是 get 方法
            if (method.getParameterTypes().length > 0) {
                continue;
            }

            // 以 get 和 is 方法名开头，说明是 getting 方法
            String name = method.getName();
            if ((name.startsWith("get") && name.length() > 3)
                    || (name.startsWith("is") && name.length() > 2)) {

                // 从方法名，获得属性，如 getId()，则属性为 id
                name = PropertyNamer.methodToProperty(name);

                // 把属性相关的方法添加到同一个list中,如 id -> ["java.lang.Object#getId","java.lang.Long#getId" ] 对应多个 get 方法
                addMethodConflict(conflictingGetters, name, method);
            }
        }

        // 解决 getting 冲突方法
        resolveGetterConflicts(conflictingGetters);
    }

    /**
     * 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 getting 方法
     */
    private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
        for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
            Method winner = null;
            String propName = entry.getKey();
            for (Method candidate : entry.getValue()) {

                // 刚开始，第一个方法为最优方法
                if (winner == null) {
                    winner = candidate;
                    continue;
                }

                // 基于返回类型比较
                Class<?> winnerType = winner.getReturnType();
                Class<?> candidateType = candidate.getReturnType();

                /**
                 * 返回值类型相同
                 * {@link ReflectorTest#shouldAllowTwoBooleanGetters}
                 */
                if (candidateType.equals(winnerType)) {
                    // 这里说明不可能存在两个相同的 getter 方法，继承的类，在 getClassMethods 已完成合并
                    if (!boolean.class.equals(candidateType)) {
                        throw new ReflectionException(
                                "Illegal overloaded getter method with ambiguous type for property "
                                        + propName + " in class " + winner.getDeclaringClass()
                                        + ". This breaks the JavaBeans specification and can cause unpredictable " +
                                        "results.");
                    }
                    // 选择 boolean 类型的 is 方法
                    else if (candidate.getName().startsWith("is")) {
                        winner = candidate;
                    }

                }
                // 当前返回值
                else if (candidateType.isAssignableFrom(winnerType)) {
                    // OK getter type is descendant
                }
                // 符合选择子类。因为子类可以修改放大返回值。例如，父类的一个方法的返回值为 List ，子类对该方法的返回值可以覆写为 ArrayList
                else if (winnerType.isAssignableFrom(candidateType)) {
                    winner = candidate;
                }
                // 返回类型冲突，抛出 ReflectionException 异常
                else {
                    throw new ReflectionException(
                            "Illegal overloaded getter method with ambiguous type for property "
                                    + propName + " in class " + winner.getDeclaringClass()
                                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                }
            }
            addGetMethod(propName, winner);
        }
    }

    private void addGetMethod(String name, Method method) {
        // 有效的属性
        if (isValidPropertyName(name)) {

            // 在这里记录 getter 方法，get 方法会被封装成 MethodInvoker
            getMethods.put(name, new MethodInvoker(method));

            // 参考：https://juejin.im/post/5adefaba518825670e5cb44d
            Type returnType = TypeParameterResolver.resolveReturnType(method, type);
            getTypes.put(name, typeToClass(returnType));
        }
    }

    private void addSetMethods(Class<?> cls) {
        // 属性与其 setting 方法的映射
        Map<String, List<Method>> conflictingSetters = new HashMap<>();

        // 获得所有方法
        Method[] methods = getClassMethods(cls);
        for (Method method : methods) {
            String name = method.getName();
            // 方法名为 set 开头
            if (name.startsWith("set") && name.length() > 3) {

                // 方法参数为 1 个
                if (method.getParameterTypes().length == 1) {
                    // 获得属性
                    name = PropertyNamer.methodToProperty(name);

                    // 添加到 conflictingSetters 中
                    addMethodConflict(conflictingSetters, name, method);
                }
            }
        }
        resolveSetterConflicts(conflictingSetters);
    }

    private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
        List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
        list.add(method);
    }

    /**
     * 解决 setting 冲突方法
     * <p>
     * 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 setting 方法
     */
    private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
        for (String propName : conflictingSetters.keySet()) {
            // 获得所有该属性的 setter 方法
            List<Method> setters = conflictingSetters.get(propName);

            // 获得 getter 方法返回值类型
            Class<?> getterType = getTypes.get(propName);
            Method match = null;
            ReflectionException exception = null;

            // 遍历属性对应的 setting 方法
            for (Method setter : setters) {
                Class<?> paramType = setter.getParameterTypes()[0];

                // 和 getter Type 相同，直接使用
                if (paramType.equals(getterType)) {
                    // should be the best match
                    match = setter;
                    break;
                }
                if (exception == null) {
                    try {
                        // 选择一个最合适匹配的
                        match = pickBetterSetter(match, setter, propName);
                    } catch (ReflectionException e) {
                        // there could still be the 'best match'
                        match = null;
                        exception = e;
                    }
                }
            }
            if (match == null) {
                throw exception;
            } else {
                // 添加到 setMethods 和 setTypes 中
                addSetMethod(propName, match);
            }
        }
    }

    /**
     * @param setter1  当前合适的方法
     * @param setter2  需要比较的方法
     * @param property 属性
     */
    private Method pickBetterSetter(Method setter1, Method setter2, String property) {
        if (setter1 == null) {
            return setter2;
        }
        Class<?> paramType1 = setter1.getParameterTypes()[0];
        Class<?> paramType2 = setter2.getParameterTypes()[0];
        if (paramType1.isAssignableFrom(paramType2)) {
            return setter2;
        } else if (paramType2.isAssignableFrom(paramType1)) {
            return setter1;
        }
        throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
                + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
                + paramType2.getName() + "'.");
    }

    /**
     * 把 setter 方法添加到缓存中，setMethods、setTypes
     */
    private void addSetMethod(String name, Method method) {
        if (isValidPropertyName(name)) {
            setMethods.put(name, new MethodInvoker(method));
            Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
            setTypes.put(name, typeToClass(paramTypes[0]));
        }
    }

    private Class<?> typeToClass(Type src) {
        Class<?> result = null;
        if (src instanceof Class) {
            result = (Class<?>) src;
        } else if (src instanceof ParameterizedType) {
            result = (Class<?>) ((ParameterizedType) src).getRawType();
        } else if (src instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) src).getGenericComponentType();
            if (componentType instanceof Class) {
                result = Array.newInstance((Class<?>) componentType, 0).getClass();
            } else {
                Class<?> componentClass = typeToClass(componentType);
                result = Array.newInstance((Class<?>) componentClass, 0).getClass();
            }
        }
        if (result == null) {
            result = Object.class;
        }
        return result;
    }

    private void addFields(Class<?> clazz) {
        // 获得所有 field
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            // 添加到 setter 相关缓存中
            if (!setMethods.containsKey(field.getName())) {
                // issue #379 - removed the check for final because JDK 1.5 allows
                // modification of final fields through reflection (JSR-133). (JGB)
                // pr #16 - final static can only be set by the classloader

                // 过滤掉以 static final 声明的属性
                int modifiers = field.getModifiers();
                if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
                    addSetField(field);
                }
            }
            // 添加到 getter 相关缓存中
            if (!getMethods.containsKey(field.getName())) {
                addGetField(field);
            }
        }
        // 递归，处理父类
        if (clazz.getSuperclass() != null) {
            addFields(clazz.getSuperclass());
        }
    }

    private void addSetField(Field field) {
        // 判断是合理的属性
        if (isValidPropertyName(field.getName())) {
            // 注意这里是封装为 SetFieldInvoker
            // 添加到 setMethods 中
            setMethods.put(field.getName(), new SetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);

            // 添加到 setTypes 中
            setTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    private void addGetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            // 添加到 getMethods 中，注意这里封装为 GetFieldInvoker
            getMethods.put(field.getName(), new GetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            // 添加到 getTypes 中
            getTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    private boolean isValidPropertyName(String name) {
        return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
    }

    /**
     * This method returns an array containing all methods declared in this class and any superclass. We use this
     * method, instead of the simpler Class.getMethods(), because we want to look for private methods as well.
     *
     * @param cls The class
     * @return An array containing all methods in this class
     */
    private Method[] getClassMethods(Class<?> cls) {
        // 每个方法签名与该方法的映射
        Map<String, Method> uniqueMethods = new HashMap<>();
        Class<?> currentClass = cls;
        while (currentClass != null && currentClass != Object.class) {
            // 通过反射拿到所有方法
            addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

            // we also need to look for interface methods -
            // because the class may be abstract

            // 由于类可能是 abstract 的抽象类，所以需要记录接口中定义的方法
            Class<?>[] interfaces = currentClass.getInterfaces();
            for (Class<?> anInterface : interfaces) {
                /**
                 * 这里接口使用了泛型，则使用Object，{@link ReflectorTest#testGetSetterType} 调试下
                 */
                addUniqueMethods(uniqueMethods, anInterface.getMethods());
            }

            // 递归父类
            currentClass = currentClass.getSuperclass();
        }

        // 执行这里，说明已经把该类的所有涉及到的方法，都记录了
        Collection<Method> methods = uniqueMethods.values();

        // 返回 Method[] 数组
        return methods.toArray(new Method[methods.size()]);
    }

    private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
        for (Method currentMethod : methods) {
            // 不是桥接方法
            // 参考：https://www.zhihu.com/question/54895701/answer/141623158
            if (!currentMethod.isBridge()) {

                // 获得方法签名
                String signature = getSignature(currentMethod);
                // check to see if the method is already known
                // if it is known, then an extended class must have
                // overridden a method
                // 当 uniqueMethods 不存在时，进行添加
                if (!uniqueMethods.containsKey(signature)) {
                    uniqueMethods.put(signature, currentMethod);
                }
            }
        }
    }

    /**
     * 格式：returnType#方法名:参数名1,参数名2,参数名3
     * <p>
     * 例如：void#checkPackageAccess:java.lang.ClassLoader,boolean
     */
    private String getSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        Class<?> returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getName()).append('#');
        }
        sb.append(method.getName());
        // 方法参数
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i == 0) {
                sb.append(':');
            } else {
                sb.append(',');
            }
            sb.append(parameters[i].getName());
        }
        return sb.toString();
    }

    /**
     * Gets the name of the class the instance provides information for
     *
     * @return The class name
     */
    public Class<?> getType() {
        return type;
    }

    public Constructor<?> getDefaultConstructor() {
        if (defaultConstructor != null) {
            return defaultConstructor;
        } else {
            throw new ReflectionException("There is no default constructor for " + type);
        }
    }

    public boolean hasDefaultConstructor() {
        return defaultConstructor != null;
    }

    public Invoker getSetInvoker(String propertyName) {
        Invoker method = setMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException(
                    "There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    public Invoker getGetInvoker(String propertyName) {
        Invoker method = getMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException(
                    "There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /**
     * Gets the type for a property setter
     *
     * @param propertyName - the name of the property
     * @return The Class of the property setter
     */
    public Class<?> getSetterType(String propertyName) {
        Class<?> clazz = setTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException(
                    "There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets the type for a property getter
     *
     * @param propertyName - the name of the property
     * @return The Class of the property getter
     */
    public Class<?> getGetterType(String propertyName) {
        Class<?> clazz = getTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException(
                    "There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets an array of the readable properties for an object
     *
     * @return The array
     */
    public String[] getGetablePropertyNames() {
        return readablePropertyNames;
    }

    /**
     * Gets an array of the writable properties for an object
     *
     * @return The array
     */
    public String[] getSetablePropertyNames() {
        return writeablePropertyNames;
    }

    /**
     * Check to see if a class has a writable property by name
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a writable property by the name
     */
    public boolean hasSetter(String propertyName) {
        return setMethods.keySet().contains(propertyName);
    }

    /**
     * Check to see if a class has a readable property by name
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a readable property by the name
     */
    public boolean hasGetter(String propertyName) {
        return getMethods.keySet().contains(propertyName);
    }

    public String findPropertyName(String name) {
        return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
    }
}
