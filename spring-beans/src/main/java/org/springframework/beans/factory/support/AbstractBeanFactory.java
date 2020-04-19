/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.PropertyEditor;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.PropertyEditorRegistrySupport;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanIsAbstractException;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.Scope;
import org.springframework.core.DecoratingClassLoader;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Abstract base class for {@link org.springframework.beans.factory.BeanFactory}
 * implementations, providing the full capabilities of the
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory} SPI.
 * Does <i>not</i> assume a listable bean factory: can therefore also be used
 * as base class for bean factory implementations which obtain bean definitions
 * from some backend resource (where bean definition access is an expensive operation).
 *
 * <p>This class provides a singleton cache (through its base class
 * {@link org.springframework.beans.factory.support.DefaultSingletonBeanRegistry},
 * singleton/prototype determination, {@link org.springframework.beans.factory.FactoryBean}
 * handling, aliases, bean definition merging for child bean definitions,
 * and bean destruction ({@link org.springframework.beans.factory.DisposableBean}
 * interface, custom destroy methods). Furthermore, it can manage a bean factory
 * hierarchy (delegating to the parent in case of an unknown bean), through implementing
 * the {@link org.springframework.beans.factory.HierarchicalBeanFactory} interface.
 *
 * <p>The main template methods to be implemented by subclasses are
 * {@link #getBeanDefinition} and {@link #createBean}, retrieving a bean definition
 * for a given bean name and creating a bean instance for a given bean definition,
 * respectively. Default implementations of those operations can be found in
 * {@link DefaultListableBeanFactory} and {@link AbstractAutowireCapableBeanFactory}.
 *
 * @marker rutine
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @since 15 April 2001
 * @see #getBeanDefinition
 * @see #createBean
 * @see AbstractAutowireCapableBeanFactory#createBean
 * @see DefaultListableBeanFactory#getBeanDefinition
 */
public abstract class AbstractBeanFactory extends FactoryBeanRegistrySupport implements ConfigurableBeanFactory {

    /**
     * <prev>
     *     <em>父工厂, 实现bean的继承、层级</em>
     *     <p>1.继承, 获取当前工厂bean定义, 获取父工厂bean定义, 用当前工厂bean定义合并和覆盖父工厂bean定义得到最终bean定义</p>
     *     <p>2.层级, 当前工厂检索bean实例, 如果当前工厂检索不到, 在父工厂检索bean实例</p>
     * </prev>
     */
    /** Parent bean factory, for bean inheritance support */
    private BeanFactory parentBeanFactory;

    /**
     * <prev>
     *     <em>默认bean类型加载器</em>
     *     <p>1.根据bean定义的类型名称加载bean类型, 并缓存在bean定义中</p>
     * </prev>
     */
    /** ClassLoader to resolve bean class names with, if necessary */
    private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

    /**
     * <prev>
     *     <em>bean类加载器</em>
     *     <p>1.根据bean定义的类型名称加载bean类型. 用于检测bean类型, 但不会缓存在bean定义中. 如仅检查依赖关系</p>
     * </prev>
     */
    /** ClassLoader to temporarily resolve bean class names with, if necessary */
    private ClassLoader tempClassLoader;

    /**
     * <prev>
     *     <em>缓存定义bean元数据标识</em>
     *     <p>1.默认缓存</p>
     * </prev>
     */
    /** Whether to cache bean metadata or rather reobtain it for every access */
    private boolean cacheBeanMetadata = true;

    /**
     * <prev>
     *     <em>定义bean表达式解析器</em>
     *     <p>1.如beanName=${expressName} => beanName=evaluateName</p>
     * </prev>
     */
    /** Resolution strategy for expressions in bean definition values */
    private BeanExpressionResolver beanExpressionResolver;

    /**
     * <prev>
     *     <em>替代PropertyEditors</em>
     *     <p>1.如果在类型转换找不到对应的PropertyEditor时, 回退用Spring ConversionService进行转换</p>
     * </prev>
     */
    /** Spring ConversionService to use instead of PropertyEditors */
    private ConversionService conversionService;

    /**
     * <prev>
     *     <em>注册自定义转换bean值的PropertyEditorRegistrars</em>
     *     <p>1.无</p>
     * </prev>
     */
    /** Custom PropertyEditorRegistrars to apply to the beans of this factory */
    private final Set<PropertyEditorRegistrar> propertyEditorRegistrars =
            new LinkedHashSet<PropertyEditorRegistrar>(4);

    /**
     * <prev>
     *     <em>缓存自定义PropertyEditor</em>
     *     <p>1.如写一个把字符串"rutine, 18"转换成对象User{String name, int age}的属性编辑器</p>
     * </prev>
     */
    /** Custom PropertyEditors to apply to the beans of this factory */
    private final Map<Class<?>, Class<? extends PropertyEditor>> customEditors =
            new HashMap<Class<?>, Class<? extends PropertyEditor>>(4);

    /**
     * <prev>
     *     <em>自定义类型转换器</em>
     *     <p>1.A custom TypeConverter to use, overriding the default PropertyEditor mechanism</p>
     * </prev>
     */
    /** A custom TypeConverter to use, overriding the default PropertyEditor mechanism */
    private TypeConverter typeConverter;

    /** String resolvers to apply e.g. to annotation attribute values */
    private final List<StringValueResolver> embeddedValueResolvers = new LinkedList<StringValueResolver>();

    /** BeanPostProcessors to apply in createBean */
    private final List<BeanPostProcessor> beanPostProcessors = new ArrayList<BeanPostProcessor>();

    /**
     * <prev>
     *     <em>标志是否存在InstantiationAwareBeanPostProcessors这样的处理器</em>
     *     <p>1.</p>
     * </prev>
     */
    /** Indicates whether any InstantiationAwareBeanPostProcessors have been registered */
    private boolean hasInstantiationAwareBeanPostProcessors;

    /**
     * <prev>
     *     <em>标志是否存在DestructionAwareBeanPostProcessors这样的处理器</em>
     *     <p>1.</p>
     * </prev>
     */
    /** Indicates whether any DestructionAwareBeanPostProcessors have been registered */
    private boolean hasDestructionAwareBeanPostProcessors;

    /** Map from scope identifier String to corresponding Scope */
    private final Map<String, Scope> scopes = new LinkedHashMap<String, Scope>(8);

    /** Security context used when running with a SecurityManager */
    private SecurityContextProvider securityContextProvider;

    /**
     * <prev>
     *     <em>映射: beanName -> merged RootBeanDefinition</em>
     *     <p>1.merged RootBeanDefinition: C = B extends A.</p>
     * </prev>
     */
    /** Map from bean name to merged RootBeanDefinition */
    private final Map<String, RootBeanDefinition> mergedBeanDefinitions =
            new ConcurrentHashMap<String, RootBeanDefinition>(256);

    /** Names of beans that have already been created at least once */
    private final Set<String> alreadyCreated =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>(256));

    /** Names of beans that are currently in creation */
    private final ThreadLocal<Object> prototypesCurrentlyInCreation =
            new NamedThreadLocal<Object>("Prototype beans currently in creation");


    /**
     * 创建AbstractBeanFactory实例
     */
    public AbstractBeanFactory() {
    }

    /**
     * 创建AbstractBeanFactory实例
     * @param parentBeanFactory 父工厂, 或者{@code null}
     * @see #getBean
     */
    public AbstractBeanFactory(BeanFactory parentBeanFactory) {
        this.parentBeanFactory = parentBeanFactory;
    }


    //---------------------------------------------------------------------
    // 以下是BeanFactory 接口的方法实现
    //---------------------------------------------------------------------

    @Override
    public Object getBean(String name) throws BeansException {
        return doGetBean(name, null, null, false);
    }

    @Override
    public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
        return doGetBean(name, requiredType, null, false);
    }

    @Override
    public Object getBean(String name, Object... args) throws BeansException {
        return doGetBean(name, null, args, false);
    }

    /**
     * 返回指定名称的单例或者单例bean
     *
     * @param name 被检索bean名称
     * @param requiredType 期望bean实例类型(使用{@code TypeConverter}转换)
     * @param args 如果是创建新实例, 创建实例具体参数(bean实例已存在, 可为{@code null})
     * @return 返回bean实例
     * @throws BeansException if the bean could not be created
     */
    public <T> T getBean(String name, Class<T> requiredType, Object... args) throws BeansException {
        return doGetBean(name, requiredType, args, false);
    }

    /**
     * 返回指定名称的单例或者单例bean
     *
     * <prev>
     *     <em>分析逻辑</em>
     *     <p>1.在缓存检索bean单例, 有则跳到[6]</p>
     *     <p>2.如果正在创建多例, 抛出异常, 如循环引用场景</p>
     *     <p>3.当前工厂不存在bean实例和定义, 从父工厂检索</p>
     *     <p>4.如果非类型检测, 需要设置创建可用bean实例</p>
     *     <p>5.根据定义创建bean实例(单例、多例或自定义范围的实例)</p>
     *     <p>6.如果期望具体的返回类型, 进行转换</p>
     * </prev>
     *
     * @param name 被检索bean名称
     * @param requiredType 期望bean实例类型(使用{@code TypeConverter}转换)
     * @param args 如果是创建新实例, 创建实例具体参数(bean实例已存在, 可为{@code null})
     * @param typeCheckOnly 是否仅仅检查bean类型
     * @return 返回bean实例
     * @throws BeansException if the bean could not be created
     */
    @SuppressWarnings("unchecked")
    protected <T> T doGetBean(
            final String name, final Class<T> requiredType, final Object[] args, boolean typeCheckOnly)
            throws BeansException {

        final String beanName = transformedBeanName(name);
        Object bean;

        // 1.在缓存检索bean单例, 有则跳到[6]
        // Eagerly check singleton cache for manually registered singletons.
        Object sharedInstance = getSingleton(beanName);
        if (sharedInstance != null && args == null) {
            if (logger.isDebugEnabled()) {
                if (isSingletonCurrentlyInCreation(beanName)) {
                    logger.debug("Returning eagerly cached instance of singleton bean '" + beanName +
                            "' that is not fully initialized yet - a consequence of a circular reference");
                }
                else {
                    logger.debug("Returning cached instance of singleton bean '" + beanName + "'");
                }
            }
            // 根据name判断返回普通bean实例还是BeanFactory实例
            bean = getObjectForBeanInstance(sharedInstance, name, beanName, null);
        }

        else {
            // 2.如果正在创建多例, 抛出异常,
            // 如: 我实例完成前需依赖其他bean, 转而先执行其他bean的实例过程, 其他bean实例过程又依赖我的实例,
            // 这种循环引用场景就会出现这种情况
            // Fail if we're already creating this bean instance:
            // We're assumably within a circular reference.
            if (isPrototypeCurrentlyInCreation(beanName)) {
                throw new BeanCurrentlyInCreationException(beanName);
            }

            // 3.当前工厂不存在bean实例和定义, 从父工厂检索
            // Check if bean definition exists in this factory.
            BeanFactory parentBeanFactory = getParentBeanFactory();
            if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
                // Not found -> check parent.
                String nameToLookup = originalBeanName(name);
                if (args != null) {
                    // 参数非空.
                    // Delegation to parent with explicit args.
                    return (T) parentBeanFactory.getBean(nameToLookup, args);
                }
                else {
                    // 参数为空, 调用标准检索bean方法.
                    // No args -> delegate to standard getBean method.
                    return parentBeanFactory.getBean(nameToLookup, requiredType);
                }
            }

            // 4.如果非类型检查, 设置创建可用bean实例
            if (!typeCheckOnly) {
                markBeanAsCreated(beanName);
            }

            // 5.根据定义创建bean实例(单例、多例或自定义范围的实例)
            try {
                // 检索最终定义bean
                final RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
                checkMergedBeanDefinition(mbd, beanName, args);//抽象的bean定义不能创建实例

				// 初始化当前bean时, 保证依赖的其他bean已存在或者创建[可能导致循环依赖]
                //Guarantee initialization of beans that the current bean depends on.
				String[] dependsOn = mbd.getDependsOn();
				if (dependsOn != null) {
					for (String dep : dependsOn) {
						if (isDependent(beanName, dep)) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"Circular depends-on relationship between '" + beanName + "' and '" + dep + "'");
						}
						registerDependentBean(dep, beanName);
						try {getBean(dep);
					}
				catch (NoSuchBeanDefinitionException ex) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"'" + beanName + "' depends on missing bean '" + dep + "'", ex);
						}
					}}

                // 创建单例bean.
                // Create bean instance.
                if (mbd.isSingleton()) {
                    sharedInstance = getSingleton(beanName, new ObjectFactory<Object>() {
                        @Override
                        public Object getObject() throws BeansException {
                            try {
                                return createBean(beanName, mbd, args);
                            }
                            catch (BeansException ex) {
                                // Explicitly remove instance from singleton cache: It might have been put there
                                // eagerly by the creation process, to allow for circular reference resolution.
                                // Also remove any beans that received a temporary reference to the bean.
                                destroySingleton(beanName);
                                throw ex;
                            }
                        }
                    });
                    bean = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
                }

                // 创建多例bean.
                else if (mbd.isPrototype()) {
                    // It's a prototype -> create a new instance.
                    Object prototypeInstance = null;
                    try {
                        beforePrototypeCreation(beanName);
                        prototypeInstance = createBean(beanName, mbd, args);
                    }
                    finally {
                        afterPrototypeCreation(beanName);
                    }
                    bean = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
                }

                // 根据Scope定义创建自定义范围bean
                else {
                    String scopeName = mbd.getScope();
                    final Scope scope = this.scopes.get(scopeName);
                    if (scope == null) {
                        throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
                    }
                    try {
                        Object scopedInstance = scope.get(beanName, new ObjectFactory<Object>() {
                            @Override
                            public Object getObject() throws BeansException {
                                beforePrototypeCreation(beanName);
                                try {
                                    return createBean(beanName, mbd, args);
                                }
                                finally {
                                    afterPrototypeCreation(beanName);
                                }
                            }
                        });
                        bean = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
                    }
                    catch (IllegalStateException ex) {
                        throw new BeanCreationException(beanName,
                                "Scope '" + scopeName + "' is not active for the current thread; consider " +
                                "defining a scoped proxy for this bean if you intend to refer to it from a singleton",
                                ex);
                    }
                }
            }
            catch (BeansException ex) {
                cleanupAfterBeanCreationFailure(beanName);
                throw ex;
            }
        }

        // 6.如果期望具体的返回类型, 进行转换
        // Check if required type matches the type of the actual bean instance.
        if (requiredType != null && bean != null && !requiredType.isInstance(bean)) {
            try {
                return getTypeConverter().convertIfNecessary(bean, requiredType);
            }
            catch (TypeMismatchException ex) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Failed to convert bean '" + name + "' to required type '" +
                            ClassUtils.getQualifiedName(requiredType) + "'", ex);
                }
                throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
            }
        }
        return (T) bean;
    }

    /**
     * <prev>
     *     <em>分析逻辑</em>
     *     <p>1.当前工厂是否存在单例或定义bean</p>
     *     <p>2.name=factoryBeanName: bean须为FactoryBean类型</p>
     *     <p>3.name=ordinaryBeanName: 普通bean或者FactoryBean</p>
     *     <p>4.如果当前工厂检索不到, 从父工厂检索, 重复[1-3]</p>
     * </prev>
     *
     * @param name 要检索的bean名称
     * @return
     */
    @Override
    public boolean containsBean(String name) {
        // 1.当前工厂是否存在单例或定义bean
        String beanName = transformedBeanName(name);
        if (containsSingleton(beanName) || containsBeanDefinition(beanName)) {
            return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(name));
        }

        // 2.如果当前工厂检索不到, 从父工厂检索
        // Not found -> check parent.
        BeanFactory parentBeanFactory = getParentBeanFactory();
        return (parentBeanFactory != null && parentBeanFactory.containsBean(originalBeanName(name)));
    }

    /**
     * <prev>
     *     <em>分析逻辑</em>
     *     <p>1.当前工厂是否缓存单例bean</p>
     *     <p>2.是否存在bean实例(空实例), 存在则true</p>
     *     <p>3.如果当前工厂不存在bean实例和定义, 在父工厂中判断</p>
     *     <p>4.定义bean是单例, 根据name分几种情况判断</p>
     *     <p>5.其他返回false</p>
     * </prev>
     *
     * @param name 要检索的bean名称
     * @return
     * @throws NoSuchBeanDefinitionException
     */
    @Override
    public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
        String beanName = transformedBeanName(name);

        // 1.当前工厂是否缓存bean单例
        Object beanInstance = getSingleton(beanName, false);
        if (beanInstance != null) {
            // FactoryBean实例:
            // a.true: name=factoryBeanName
            // b.true: name=ordinaryBeanName, FactoryBean实例须只创建bean单例
            if (beanInstance instanceof FactoryBean) {
                return (BeanFactoryUtils.isFactoryDereference(name) || ((FactoryBean<?>) beanInstance).isSingleton());
            }

            // 非FactoryBean实例:
            // a.true: name!=factoryBeanName
            // b.false: name=factoryBeanName, 实际是普通bean实例
            else {
                return !BeanFactoryUtils.isFactoryDereference(name);
            }
        }

        // 2.是否存在bean实例(空实例), 存在则true
        else if (containsSingleton(beanName)) {
            return true;
        }

        // 3.如果当前工厂不存在bean实例和定义, 在父工厂中判断
        // No singleton instance found -> check bean definition.
        BeanFactory parentBeanFactory = getParentBeanFactory();
        if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
            // No bean definition found in this factory -> delegate to parent.
            return parentBeanFactory.isSingleton(originalBeanName(name));
        }

        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

        // 4.定义bean是单例, 根据name分几种情况判断
        // In case of FactoryBean, return singleton status of created object if not a dereference.
        if (mbd.isSingleton()) {
            // FactoryBean类型:
            // a.true: name=factoryBeanName
            // b.true: name=ordinaryBeanName, FactoryBean实例须只创建bean单例
            if (isFactoryBean(beanName, mbd)) {
                if (BeanFactoryUtils.isFactoryDereference(name)) {
                    return true;
                }
                FactoryBean<?> factoryBean = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
                return factoryBean.isSingleton();
            }

            // 非FactoryBean实例:
            // a.true: name!=factoryBeanName
            // b.false: name=factoryBeanName, 实际是普通bean实例
            else {
                return !BeanFactoryUtils.isFactoryDereference(name);
            }
        }

        // 5.其他返回false
        else {
            return false;
        }
    }

    /**
     * <prev>
     *     <em>分析逻辑</em>
     *     <p>1.如果当前工厂不存在定义bean, 在父工厂中判断</p>
     *     <p>2.定义bean为原型类型, 根据name分几种情况判断</p>
     *     <p>3.false: name=factoryBeanName, 不管是FactoryBean还是普通bean</p>
     *     <p>4.虽然是单例或范围实例, 对name=ordinaryBeanName的FactoryBean实例需要再次判断</p>
     *     <p>5.其他返回false</p>
     * </prev>
     *
     * @param name 要检索的bean名称
     * @return
     * @throws NoSuchBeanDefinitionException
     */
    @Override
    public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
        String beanName = transformedBeanName(name);

        // 1.如果当前工厂不存在定义bean, 在父工厂中判断
        BeanFactory parentBeanFactory = getParentBeanFactory();
        if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
            // No bean definition found in this factory -> delegate to parent.
            return parentBeanFactory.isPrototype(originalBeanName(name));
        }

        // 2.定义bean为原型类型, 根据name分几种情况判断
        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
        if (mbd.isPrototype()) {
            // a.true: name=ordinaryBeanName
            // b.true: name=factoryBeanName, bean必须是FactoryBean类型
            // In case of FactoryBean, return singleton status of created object if not a dereference.
            return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName, mbd));
        }

        // 3.false: name=factoryBeanName, 不管是FactoryBean还是普通bean
        // FactoryBean实例一般都是单例, 但它可以创建单例或者原型实例
        // Singleton or scoped - not a prototype.
        // However, FactoryBean may still produce a prototype object...
        if (BeanFactoryUtils.isFactoryDereference(name)) {
            return false;
        }

        // 4.虽然是单例或范围实例, 对name=ordinaryBeanName的FactoryBean实例需要再次判断
        if (isFactoryBean(beanName, mbd)) {
            final FactoryBean<?> fb = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
            if (System.getSecurityManager() != null) {
                return AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
                    @Override
                    public Boolean run() {
                        return ((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
                                !fb.isSingleton());
                    }
                }, getAccessControlContext());
            }
            else {
                return ((fb instanceof SmartFactoryBean && ((SmartFactoryBean<?>) fb).isPrototype()) ||
                        !fb.isSingleton());
            }
        }

        // 5.其他返回false
        else {
            return false;
        }
    }

    /**
     * <prev>
     *     <em>分析逻辑</em>
     *     <p>1.如果当前工厂可以获得单例bean, 不存在跳到[4]</p>
     *     <p>
     *         2.FactoryBean实例:
     *         2.1.name=ordinaryBeanName, 匹配由该bean创建的实例类型
     *         2.2.name=factoryBeanName, 直接匹配bean实例
     *     </p>
     *     <p>3.非FactoryBean实例: 非factoryBeanName, 匹配bean实例</p>
     *     <p>4.如果当前工厂不存在单例bean且不存在定义bean, 但存在实例(空实例), 直接返回false</p>
     *     <p>5.当前工厂不存在单例bean和定义, 检索父工厂, 重复[1-4]</p>
     *     <p>
     *         6.当前工厂存在bean定义:
     *         6.1.包装bean: 非factoryBeanName, 匹配包装bean类型
     *         6.2.非包装bean: FactoryBean实例, 非factoryBeanName, 匹配真正bean类型
     *         6.3.非FactoryBean实例且检索FactoryBean类型(特殊)
     *     </p>
     *     <p>7.直接判断bean类型</p>
     * </prev>
     *
     * @param name 要检索的bean名称
     * @param typeToMatch 期望bean的类型
     * @return
     * @throws NoSuchBeanDefinitionException
     */
    @Override
    public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
        String beanName = transformedBeanName(name);

		// Check manually registered singletons.
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			if (beanInstance instanceof FactoryBean) {
				if (!BeanFactoryUtils.isFactoryDereference(name)) {
					Class<?> type = getTypeForFactoryBean((FactoryBean<?>) beanInstance);
					return (type != null && typeToMatch.isAssignableFrom(type));
				}
				else {
					return typeToMatch.isInstance(beanInstance);
				}
			}
			else if (!BeanFactoryUtils.isFactoryDereference(name)) {
				if (typeToMatch.isInstance(beanInstance)) {
					// Direct match for exposed instance?
					return true;
				}
				//泛型参数匹配else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
					// Generics potentially only match on the target class, not on the proxy...
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					Class<?> targetType = mbd.getTargetType();
					if (targetType != null && targetType != ClassUtils.getUserClass(beanInstance) ) {

						// Check raw class match as well, making sure it's exposed on the proxy.
						Class<?> classToMatch = typeToMatch.resolve();
						if (classToMatch != null && ! classToMatch.isInstance(beanInstance)){
					return false;
						}
						if (typeToMatch.isAssignableFrom(targetType)) {
							return true;
						}
					}
					ResolvableType resolvableType = mbd.targetType;
					if (resolvableType == null) {
						resolvableType = mbd.factoryMethodReturnType;
					}
					return (resolvableType != null && typeToMatch.isAssignableFrom(resolvableType));
				}
			}
			return false;
		}
		else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// null instance registered
			return false;
		}

        // No singleton instance found -> check bean definition.
        BeanFactory parentBeanFactory = getParentBeanFactory();
        if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
            // No bean definition found in this factory -> delegate to parent.
            return parentBeanFactory.isTypeMatch(originalBeanName(name), typeToMatch);
        }

        // Retrieve corresponding bean definition.
        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

        Class<?> classToMatch = typeToMatch.resolve();
        if (classToMatch == null) {
            classToMatch = FactoryBean.class;
        }
        Class<?>[] typesToMatch = (FactoryBean.class == classToMatch ?
                new Class<?>[] {classToMatch} : new Class<?>[] {FactoryBean.class, classToMatch});

        // 不懂这段..............
        // Check decorated bean definition, if any: We assume it'll be easier
        // to determine the decorated bean's type than the proxy's type.
        BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
        if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
            RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
            Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd, typesToMatch);
            if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
                return typeToMatch.isAssignableFrom(targetClass);
            }
        }

        Class<?> beanType = predictBeanType(beanName, mbd, typesToMatch);
        if (beanType == null) {
            return false;
        }

        // Check bean class whether we're dealing with a FactoryBean.
        if (FactoryBean.class.isAssignableFrom(beanType)) {
            if (!BeanFactoryUtils.isFactoryDereference(name)) {
                // If it's a FactoryBean, we want to look at what it creates, not the factory class.
                beanType = getTypeForFactoryBean(beanName, mbd);
                if (beanType == null) {
                    return false;
                }
            }
        }
        else if (BeanFactoryUtils.isFactoryDereference(name)) {
            // Special case: A SmartInstantiationAwareBeanPostProcessor returned a non-FactoryBean
            // type but we nevertheless are being asked to dereference a FactoryBean...
            // Let's check the original bean class and proceed with it if it is a FactoryBean.
            beanType = predictBeanType(beanName, mbd, FactoryBean.class);
            if (beanType == null || !FactoryBean.class.isAssignableFrom(beanType)) {
                return false;
            }
        }

        ResolvableType resolvableType = mbd.targetType;
        if (resolvableType == null) {
            resolvableType = mbd.factoryMethodReturnType;
        }
        if (resolvableType != null && resolvableType.resolve() == beanType) {
            return typeToMatch.isAssignableFrom(resolvableType);
        }
        return typeToMatch.isAssignableFrom(beanType);
    }

    @Override
    public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
        return isTypeMatch(name, ResolvableType.forRawClass(typeToMatch));
    }

    /**
     * <prev>
     *     <em>分析逻辑</em>
     *     <p>1.如果当前工厂存在单例bean, 不存在跳到[4]</p>
     *     <p>2.FactoryBean实例: name=ordinaryBeanName, 检索FactoryBean实例的bean实例类型</p>
     *     <p>3.非FactoryBean实例: 直接返回bean实例类型</p>
     *     <p>4.如果当前工厂不存在单例bean且不存在定义bean, 但存在实例(空实例), 直接返回null</p>
     *     <p>5.当前工厂不存在单例bean和定义, 检索父工厂, 重复[1-4]</p>
     *     <p>
     *         6.当前工厂存在定义bean:
     *         a.包装bean, 非factoryBeanName, 返回包装bean类型
     *         b.非包装bean: FactoryBean实例, 非factoryBeanName, 返回真正bean类型
     *         c.直接返回bean类型(FactoryBean类型)
     *     </p>
     *     <p>7.非FactoryBean类型且非factoryBeanName则返回bean类型, 否返回null </p>
     * </prev>
     *
     * @param name 要检索的bean名称
     * @return
     * @throws NoSuchBeanDefinitionException
     */
    @Override
    public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
        String beanName = transformedBeanName(name);

        // Check manually registered singletons.
        Object beanInstance = getSingleton(beanName, false);
        if (beanInstance != null) {
            if (beanInstance instanceof FactoryBean && !BeanFactoryUtils.isFactoryDereference(name)) {
                return getTypeForFactoryBean((FactoryBean<?>) beanInstance);
            }
            else {
                // !(beanInstance instanceof FactoryBean) && BeanFactoryUtils.isFactoryDereference(name)这种情况有问题
                return beanInstance.getClass();
            }
        }
        else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
            // null instance registered
            return null;
        }

        // No singleton instance found -> check bean definition.
        BeanFactory parentBeanFactory = getParentBeanFactory();
        if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
            // No bean definition found in this factory -> delegate to parent.
            return parentBeanFactory.getType(originalBeanName(name));
        }

        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

        // Check decorated bean definition, if any: We assume it'll be easier
        // to determine the decorated bean's type than the proxy's type.
        BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
        if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
            RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
            Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd);
            if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
                return targetClass;
            }
        }

        Class<?> beanClass = predictBeanType(beanName, mbd);

        // Check bean class whether we're dealing with a FactoryBean.
        if (beanClass != null && FactoryBean.class.isAssignableFrom(beanClass)) {
            if (!BeanFactoryUtils.isFactoryDereference(name)) {
                // If it's a FactoryBean, we want to look at what it creates, not at the factory class.
                return getTypeForFactoryBean(beanName, mbd);
            }
            else {
                return beanClass;
            }
        }
        else {
            return (!BeanFactoryUtils.isFactoryDereference(name) ? beanClass : null);
        }
    }

    @Override
    public String[] getAliases(String name) {
        String beanName = transformedBeanName(name);
        List<String> aliases = new ArrayList<String>();
        boolean factoryPrefix = name.startsWith(FACTORY_BEAN_PREFIX);
        String fullBeanName = beanName;
        if (factoryPrefix) {
            fullBeanName = FACTORY_BEAN_PREFIX + beanName;
        }
        if (!fullBeanName.equals(name)) {
            aliases.add(fullBeanName);
        }
        String[] retrievedAliases = super.getAliases(beanName);
        for (String retrievedAlias : retrievedAliases) {
            String alias = (factoryPrefix ? FACTORY_BEAN_PREFIX : "") + retrievedAlias;
            if (!alias.equals(name)) {
                aliases.add(alias);
            }
        }
        if (!containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
            BeanFactory parentBeanFactory = getParentBeanFactory();
            if (parentBeanFactory != null) {
                aliases.addAll(Arrays.asList(parentBeanFactory.getAliases(fullBeanName)));
            }
        }
        return StringUtils.toStringArray(aliases);
    }


    //---------------------------------------------------------------------
    // Implementation of HierarchicalBeanFactory interface
    //---------------------------------------------------------------------

    @Override
    public BeanFactory getParentBeanFactory() {
        return this.parentBeanFactory;
    }

    @Override
    public boolean containsLocalBean(String name) {
        String beanName = transformedBeanName(name);
        return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
                (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
    }


    //---------------------------------------------------------------------
    // Implementation of ConfigurableBeanFactory interface
    //---------------------------------------------------------------------

    @Override
    public void setParentBeanFactory(BeanFactory parentBeanFactory) {
        if (this.parentBeanFactory != null && this.parentBeanFactory != parentBeanFactory) {
            throw new IllegalStateException("Already associated with parent BeanFactory: " + this.parentBeanFactory);
        }
        this.parentBeanFactory = parentBeanFactory;
    }

    @Override
    public void setBeanClassLoader(ClassLoader beanClassLoader) {
        this.beanClassLoader = (beanClassLoader != null ? beanClassLoader : ClassUtils.getDefaultClassLoader());
    }

    @Override
    public ClassLoader getBeanClassLoader() {
        return this.beanClassLoader;
    }

    @Override
    public void setTempClassLoader(ClassLoader tempClassLoader) {
        this.tempClassLoader = tempClassLoader;
    }

    @Override
    public ClassLoader getTempClassLoader() {
        return this.tempClassLoader;
    }

    @Override
    public void setCacheBeanMetadata(boolean cacheBeanMetadata) {
        this.cacheBeanMetadata = cacheBeanMetadata;
    }

    @Override
    public boolean isCacheBeanMetadata() {
        return this.cacheBeanMetadata;
    }

    @Override
    public void setBeanExpressionResolver(BeanExpressionResolver resolver) {
        this.beanExpressionResolver = resolver;
    }

    @Override
    public BeanExpressionResolver getBeanExpressionResolver() {
        return this.beanExpressionResolver;
    }

    @Override
    public void setConversionService(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @Override
    public ConversionService getConversionService() {
        return this.conversionService;
    }

    @Override
    public void addPropertyEditorRegistrar(PropertyEditorRegistrar registrar) {
        Assert.notNull(registrar, "PropertyEditorRegistrar must not be null");
        this.propertyEditorRegistrars.add(registrar);
    }

    /**
     * Return the set of PropertyEditorRegistrars.
     */
    public Set<PropertyEditorRegistrar> getPropertyEditorRegistrars() {
        return this.propertyEditorRegistrars;
    }

    @Override
    public void registerCustomEditor(Class<?> requiredType, Class<? extends PropertyEditor> propertyEditorClass) {
        Assert.notNull(requiredType, "Required type must not be null");
        Assert.notNull(propertyEditorClass, "PropertyEditor class must not be null");
        this.customEditors.put(requiredType, propertyEditorClass);
    }

    @Override
    public void copyRegisteredEditorsTo(PropertyEditorRegistry registry) {
        registerCustomEditors(registry);
    }

    /**
     * Return the map of custom editors, with Classes as keys and PropertyEditor classes as values.
     */
    public Map<Class<?>, Class<? extends PropertyEditor>> getCustomEditors() {
        return this.customEditors;
    }

    @Override
    public void setTypeConverter(TypeConverter typeConverter) {
        this.typeConverter = typeConverter;
    }

    /**
     * Return the custom TypeConverter to use, if any.
     * @return the custom TypeConverter, or {@code null} if none specified
     */
    protected TypeConverter getCustomTypeConverter() {
        return this.typeConverter;
    }

    @Override
    public TypeConverter getTypeConverter() {
        TypeConverter customConverter = getCustomTypeConverter();
        if (customConverter != null) {
            return customConverter;
        }
        else {
            // Build default TypeConverter, registering custom editors.
            SimpleTypeConverter typeConverter = new SimpleTypeConverter();
            typeConverter.setConversionService(getConversionService());
            registerCustomEditors(typeConverter);
            return typeConverter;
        }
    }

    @Override
    public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
        Assert.notNull(valueResolver, "StringValueResolver must not be null");
        this.embeddedValueResolvers.add(valueResolver);
    }

    @Override
    public boolean hasEmbeddedValueResolver() {
        return !this.embeddedValueResolvers.isEmpty();
    }

    @Override
    public String resolveEmbeddedValue(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (StringValueResolver resolver : this.embeddedValueResolvers) {
            result = resolver.resolveStringValue(result);
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    @Override
    public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
        Assert.notNull(beanPostProcessor, "BeanPostProcessor must not be null");
        this.beanPostProcessors.remove(beanPostProcessor);
        this.beanPostProcessors.add(beanPostProcessor);
        if (beanPostProcessor instanceof InstantiationAwareBeanPostProcessor) {
            this.hasInstantiationAwareBeanPostProcessors = true;
        }
        if (beanPostProcessor instanceof DestructionAwareBeanPostProcessor) {
            this.hasDestructionAwareBeanPostProcessors = true;
        }
    }

    @Override
    public int getBeanPostProcessorCount() {
        return this.beanPostProcessors.size();
    }

    /**
     * Return the list of BeanPostProcessors that will get applied
     * to beans created with this factory.
     */
    public List<BeanPostProcessor> getBeanPostProcessors() {
        return this.beanPostProcessors;
    }

    /**
     * Return whether this factory holds a InstantiationAwareBeanPostProcessor
     * that will get applied to singleton beans on shutdown.
     * @see #addBeanPostProcessor
     * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
     */
    protected boolean hasInstantiationAwareBeanPostProcessors() {
        return this.hasInstantiationAwareBeanPostProcessors;
    }

    /**
     * Return whether this factory holds a DestructionAwareBeanPostProcessor
     * that will get applied to singleton beans on shutdown.
     * @see #addBeanPostProcessor
     * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
     */
    protected boolean hasDestructionAwareBeanPostProcessors() {
        return this.hasDestructionAwareBeanPostProcessors;
    }

    @Override
    public void registerScope(String scopeName, Scope scope) {
        Assert.notNull(scopeName, "Scope identifier must not be null");
        Assert.notNull(scope, "Scope must not be null");
        if (SCOPE_SINGLETON.equals(scopeName) || SCOPE_PROTOTYPE.equals(scopeName)) {
            throw new IllegalArgumentException("Cannot replace existing scopes 'singleton' and 'prototype'");
        }
        Scope previous = this.scopes.put(scopeName, scope);
        if (previous != null && previous != scope) {
            if (logger.isInfoEnabled()) {
                logger.info("Replacing scope '" + scopeName + "' from [" + previous + "] to [" + scope + "]");
            }
        }
        else {
            if (logger.isDebugEnabled()) {
                logger.debug("Registering scope '" + scopeName + "' with implementation [" + scope + "]");
            }
        }
    }

    @Override
    public String[] getRegisteredScopeNames() {
        return StringUtils.toStringArray(this.scopes.keySet());
    }

    @Override
    public Scope getRegisteredScope(String scopeName) {
        Assert.notNull(scopeName, "Scope identifier must not be null");
        return this.scopes.get(scopeName);
    }

    /**
     * Set the security context provider for this bean factory. If a security manager
     * is set, interaction with the user code will be executed using the privileged
     * of the provided security context.
     */
    public void setSecurityContextProvider(SecurityContextProvider securityProvider) {
        this.securityContextProvider = securityProvider;
    }

    /**
     * Delegate the creation of the access control context to the
     * {@link #setSecurityContextProvider SecurityContextProvider}.
     */
    @Override
    public AccessControlContext getAccessControlContext() {
        return (this.securityContextProvider != null ?
                this.securityContextProvider.getAccessControlContext() :
                AccessController.getContext());
    }

    @Override
    public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
        Assert.notNull(otherFactory, "BeanFactory must not be null");
        setBeanClassLoader(otherFactory.getBeanClassLoader());
        setCacheBeanMetadata(otherFactory.isCacheBeanMetadata());
        setBeanExpressionResolver(otherFactory.getBeanExpressionResolver());
        setConversionService(otherFactory.getConversionService());
        if (otherFactory instanceof AbstractBeanFactory) {
            AbstractBeanFactory otherAbstractFactory = (AbstractBeanFactory) otherFactory;
            this.propertyEditorRegistrars.addAll(otherAbstractFactory.propertyEditorRegistrars);
            this.customEditors.putAll(otherAbstractFactory.customEditors);
            this.typeConverter = otherAbstractFactory.typeConverter;
            this.beanPostProcessors.addAll(otherAbstractFactory.beanPostProcessors);
            this.hasInstantiationAwareBeanPostProcessors = this.hasInstantiationAwareBeanPostProcessors ||
                    otherAbstractFactory.hasInstantiationAwareBeanPostProcessors;
            this.hasDestructionAwareBeanPostProcessors = this.hasDestructionAwareBeanPostProcessors ||
                    otherAbstractFactory.hasDestructionAwareBeanPostProcessors;
            this.scopes.putAll(otherAbstractFactory.scopes);
            this.securityContextProvider = otherAbstractFactory.securityContextProvider;
        }
        else {
            setTypeConverter(otherFactory.getTypeConverter());
            String[] otherScopeNames = otherFactory.getRegisteredScopeNames();
            for (String scopeName : otherScopeNames) {
                this.scopes.put(scopeName, otherFactory.getRegisteredScope(scopeName));
            }
        }
    }

	/**
	 * Return a 'merged' BeanDefinition for the given bean name,
	 * merging a child bean definition with its parent if necessary.
	 * <p>This {@code getMergedBeanDefinition} considers bean definition
	 * in ancestors as well.
	 * @param name the name of the bean to retrieve the merged definition for
	 * (may be an alias)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	@Override
	public BeanDefinition getMergedBeanDefinition(String name) throws BeansException {
		String beanName = transformedBeanName(name);

		// Efficiently check whether bean definition exists in this factory.
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			return ((ConfigurableBeanFactory) getParentBeanFactory()).getMergedBeanDefinition(beanName);
		}
		// Resolve merged bean definition locally.
		return getMergedLocalBeanDefinition(beanName);
	}

	@Override
	public boolean isFactoryBean(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			return (beanInstance instanceof FactoryBean);
		}
		else if (containsSingleton(beanName)) {
			// null instance registered
			return false;
		}

		// No singleton instance found -> check bean definition.
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			return ((ConfigurableBeanFactory) getParentBeanFactory()).isFactoryBean(name);

}
		return isFactoryBean(beanName, getMergedLocalBeanDefinition(beanName));
	}

    @Override
    public boolean isActuallyInCreation(String beanName) {
        return (isSingletonCurrentlyInCreation(beanName) || isPrototypeCurrentlyInCreation(beanName));
    }

    /**
     * Return whether the specified prototype bean is currently in creation
     * (within the current thread).
     * @param beanName the name of the bean
     */
    protected boolean isPrototypeCurrentlyInCreation(String beanName) {
        Object curVal = this.prototypesCurrentlyInCreation.get();
        return (curVal != null &&
                (curVal.equals(beanName) || (curVal instanceof Set && ((Set<?>) curVal).contains(beanName))));
    }

    /**
     * Callback before prototype creation.
     * <p>The default implementation register the prototype as currently in creation.
     * @param beanName the name of the prototype about to be created
     * @see #isPrototypeCurrentlyInCreation
     */
    @SuppressWarnings("unchecked")
    protected void beforePrototypeCreation(String beanName) {
        Object curVal = this.prototypesCurrentlyInCreation.get();
        if (curVal == null) {
            this.prototypesCurrentlyInCreation.set(beanName);
        }
        else if (curVal instanceof String) {
            Set<String> beanNameSet = new HashSet<String>(2);
            beanNameSet.add((String) curVal);
            beanNameSet.add(beanName);
            this.prototypesCurrentlyInCreation.set(beanNameSet);
        }
        else {
            Set<String> beanNameSet = (Set<String>) curVal;
            beanNameSet.add(beanName);
        }
    }

    /**
     * Callback after prototype creation.
     * <p>The default implementation marks the prototype as not in creation anymore.
     * @param beanName the name of the prototype that has been created
     * @see #isPrototypeCurrentlyInCreation
     */
    @SuppressWarnings("unchecked")
    protected void afterPrototypeCreation(String beanName) {
        Object curVal = this.prototypesCurrentlyInCreation.get();
        if (curVal instanceof String) {
            this.prototypesCurrentlyInCreation.remove();
        }
        else if (curVal instanceof Set) {
            Set<String> beanNameSet = (Set<String>) curVal;
            beanNameSet.remove(beanName);
            if (beanNameSet.isEmpty()) {
                this.prototypesCurrentlyInCreation.remove();
            }
        }
    }

    @Override
    public void destroyBean(String beanName, Object beanInstance) {
        destroyBean(beanName, beanInstance, getMergedLocalBeanDefinition(beanName));
    }

    /**
     * Destroy the given bean instance (usually a prototype instance
     * obtained from this factory) according to the given bean definition.
     * @param beanName the name of the bean definition
     * @param bean the bean instance to destroy
     * @param mbd the merged bean definition
     */
    protected void destroyBean(String beanName, Object bean, RootBeanDefinition mbd) {
        new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), getAccessControlContext()).destroy();
    }

    @Override
    public void destroyScopedBean(String beanName) {
        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
        if (mbd.isSingleton() || mbd.isPrototype()) {
            throw new IllegalArgumentException(
                    "Bean name '" + beanName + "' does not correspond to an object in a mutable scope");
        }
        String scopeName = mbd.getScope();
        Scope scope = this.scopes.get(scopeName);
        if (scope == null) {
            throw new IllegalStateException("No Scope SPI registered for scope name '" + scopeName + "'");
        }
        Object bean = scope.remove(beanName);
        if (bean != null) {
            destroyBean(beanName, bean, mbd);
        }
    }


    //---------------------------------------------------------------------
    // Implementation methods
    //---------------------------------------------------------------------

    /**
     * Return the bean name, stripping out the factory dereference prefix if necessary,
     * and resolving aliases to canonical names.
     * @param name the user-specified name
     * @return the transformed bean name
     */
    protected String transformedBeanName(String name) {
        return canonicalName(BeanFactoryUtils.transformedBeanName(name));
    }

    /**
     * Determine the original bean name, resolving locally defined aliases to canonical names.
     * @param name the user-specified name
     * @return the original bean name
     */
    protected String originalBeanName(String name) {
        String beanName = transformedBeanName(name);
        if (name.startsWith(FACTORY_BEAN_PREFIX)) {
            beanName = FACTORY_BEAN_PREFIX + beanName;
        }
        return beanName;
    }

    /**
     * Initialize the given BeanWrapper with the custom editors registered
     * with this factory. To be called for BeanWrappers that will create
     * and populate bean instances.
     * <p>The default implementation delegates to {@link #registerCustomEditors}.
     * Can be overridden in subclasses.
     * @param bw the BeanWrapper to initialize
     */
    protected void initBeanWrapper(BeanWrapper bw) {
        bw.setConversionService(getConversionService());
        registerCustomEditors(bw);
    }

    /**
     * Initialize the given PropertyEditorRegistry with the custom editors
     * that have been registered with this BeanFactory.
     * <p>To be called for BeanWrappers that will create and populate bean
     * instances, and for SimpleTypeConverter used for constructor argument
     * and factory method type conversion.
     * @param registry the PropertyEditorRegistry to initialize
     */
    protected void registerCustomEditors(PropertyEditorRegistry registry) {
        PropertyEditorRegistrySupport registrySupport =
                (registry instanceof PropertyEditorRegistrySupport ? (PropertyEditorRegistrySupport) registry : null);
        if (registrySupport != null) {
            registrySupport.useConfigValueEditors();
        }
        if (!this.propertyEditorRegistrars.isEmpty()) {
            for (PropertyEditorRegistrar registrar : this.propertyEditorRegistrars) {
                try {
                    registrar.registerCustomEditors(registry);
                }
                catch (BeanCreationException ex) {
                    Throwable rootCause = ex.getMostSpecificCause();
                    if (rootCause instanceof BeanCurrentlyInCreationException) {
                        BeanCreationException bce = (BeanCreationException) rootCause;
                        if (isCurrentlyInCreation(bce.getBeanName())) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("PropertyEditorRegistrar [" + registrar.getClass().getName() +
                                        "] failed because it tried to obtain currently created bean '" +
                                        ex.getBeanName() + "': " + ex.getMessage());
                            }
                            onSuppressedException(ex);
                            continue;
                        }
                    }
                    throw ex;
                }
            }
        }
        if (!this.customEditors.isEmpty()) {
            for (Map.Entry<Class<?>, Class<? extends PropertyEditor>> entry : this.customEditors.entrySet()) {
                Class<?> requiredType = entry.getKey();
                Class<? extends PropertyEditor> editorClass = entry.getValue();
                registry.registerCustomEditor(requiredType, BeanUtils.instantiateClass(editorClass));
            }
        }
    }


    /**
     * Return a merged RootBeanDefinition, traversing the parent bean definition
     * if the specified bean corresponds to a child bean definition.
     * @param beanName the name of the bean to retrieve the merged definition for
     * @return a (potentially merged) RootBeanDefinition for the given bean
     * @throws NoSuchBeanDefinitionException if there is no bean with the given name
     * @throws BeanDefinitionStoreException in case of an invalid bean definition
     */
    protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) throws BeansException {
        // Quick check on the concurrent map first, with minimal locking.
        RootBeanDefinition mbd = this.mergedBeanDefinitions.get(beanName);
        if (mbd != null) {
            return mbd;
        }
        return getMergedBeanDefinition(beanName, getBeanDefinition(beanName));
    }

    /**
     * Return a RootBeanDefinition for the given top-level bean, by merging with
     * the parent if the given bean's definition is a child bean definition.
     * @param beanName the name of the bean definition
     * @param bd the original bean definition (Root/ChildBeanDefinition)
     * @return a (potentially merged) RootBeanDefinition for the given bean
     * @throws BeanDefinitionStoreException in case of an invalid bean definition
     */
    protected RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition bd)
            throws BeanDefinitionStoreException {

        return getMergedBeanDefinition(beanName, bd, null);
    }

    /**
     * Return a RootBeanDefinition for the given bean, by merging with the
     * parent if the given bean's definition is a child bean definition.
     * @param beanName the name of the bean definition
     * @param bd the original bean definition (Root/ChildBeanDefinition)
     * @param containingBd the containing bean definition in case of inner bean,
     * or {@code null} in case of a top-level bean
     * @return a (potentially merged) RootBeanDefinition for the given bean
     * @throws BeanDefinitionStoreException in case of an invalid bean definition
     */
    protected RootBeanDefinition getMergedBeanDefinition(
            String beanName, BeanDefinition bd, BeanDefinition containingBd)
            throws BeanDefinitionStoreException {

        synchronized (this.mergedBeanDefinitions) {
            RootBeanDefinition mbd = null;

            // Check with full lock now in order to enforce the same merged instance.
            if (containingBd == null) {
                mbd = this.mergedBeanDefinitions.get(beanName);
            }

            if (mbd == null) {
                if (bd.getParentName() == null) {
                    // Use copy of given root bean definition.
                    if (bd instanceof RootBeanDefinition) {
                        mbd = ((RootBeanDefinition) bd).cloneBeanDefinition();
                    }
                    else {
                        mbd = new RootBeanDefinition(bd);
                    }
                }
                else {
                    // Child bean definition: needs to be merged with parent.
                    BeanDefinition pbd;
                    try {
                        String parentBeanName = transformedBeanName(bd.getParentName());
                        if (!beanName.equals(parentBeanName)) {
                            pbd = getMergedBeanDefinition(parentBeanName);
                        }
                        else {
                            BeanFactory parent = getParentBeanFactory();
                            if (parent instanceof ConfigurableBeanFactory) {
                                pbd = ((ConfigurableBeanFactory) parent).getMergedBeanDefinition(parentBeanName);
                            }
                            else {
                                throw new NoSuchBeanDefinitionException(parentBeanName,
                                        "Parent name '" + parentBeanName + "' is equal to bean name '" + beanName +
                                        "': cannot be resolved without a ConfigurableBeanFactory parent");
                            }
                        }
                    }
                    catch (NoSuchBeanDefinitionException ex) {
                        throw new BeanDefinitionStoreException(bd.getResourceDescription(), beanName,
                                "Could not resolve parent bean definition '" + bd.getParentName() + "'", ex);
                    }
                    // Deep copy with overridden values.
                    mbd = new RootBeanDefinition(pbd);
                    mbd.overrideFrom(bd);
                }

                // Set default singleton scope, if not configured before.
                if (!StringUtils.hasLength(mbd.getScope())) {
                    mbd.setScope(SCOPE_SINGLETON);
                }

                // A bean contained in a non-singleton bean cannot be a singleton itself.
                // Let's correct this on the fly here, since this might be the result of
                // parent-child merging for the outer bean, in which case the original inner bean
                // definition will not have inherited the merged outer bean's singleton status.
                if (containingBd != null && !containingBd.isSingleton() && mbd.isSingleton()) {
                    mbd.setScope(containingBd.getScope());
                }

				// Cache the merged bean definition for the time being
				// (it might still getre -merged later on in order to pick up metadata changes)
				if (containingBd == null && isCacheBeanMetadata()) {
					this.mergedBeanDefinitions.put(beanName, mbd);
				}
			}

            return mbd;
        }
    }

    /**
     * Check the given merged bean definition,
     * potentially throwing validation exceptions.
     * @param mbd the merged bean definition to check
     * @param beanName the name of the bean
     * @param args the arguments for bean creation, if any
     * @throws BeanDefinitionStoreException in case of validation failure
     */
    protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, Object[] args)
            throws BeanDefinitionStoreException {

        if (mbd.isAbstract()) {
            throw new BeanIsAbstractException(beanName);
        }
    }

    /**
     * Remove the merged bean definition for the specified bean,
     * recreating it on next access.
     * @param beanName the bean name to clear the merged definition for
     */
    protected void clearMergedBeanDefinition(String beanName) {
        this.mergedBeanDefinitions.remove(beanName);
    }

    /**
     * Clear the merged bean definition cache, removing entries for beans
     * which are not considered eligible for full metadata caching yet.
     * <p>Typically triggered after changes to the original bean definitions,
     * e.g. after applying a {@code BeanFactoryPostProcessor}. Note that metadata
     * for beans which have already been created at this point will be kept around.
     * @since 4.2
     */
    public void clearMetadataCache() {
        Iterator<String> mergedBeans = this.mergedBeanDefinitions.keySet().iterator();
        while (mergedBeans.hasNext()) {
            if (!isBeanEligibleForMetadataCaching(mergedBeans.next())) {
                mergedBeans.remove();
            }
        }
    }

	/**
	 * Resolve the bean class for the specified bean definition,
	 * resolving a bean class name into a Class reference (if necessary)
	 * and storing the resolved Class in the bean definition for further use.
	 * @param mbd the merged bean definition to determine the class for
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the resolved bean class (or {@code null} if none)
	 * @throws CannotLoadBeanClassException if we failed to load the class
	 */
	protected Class<?> resolveBeanClass(final RootBeanDefinition mbd, String beanName, final Class<?>... typesToMatch)
			throws CannotLoadBeanClassException {
		try {
			if (mbd.hasBeanClass()) {
				return mbd.getBeanClass();
			}
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged(new PrivilegedExceptionAction<Class<?>>() {
					@Override
					public Class<?> run() throws Exception {
						return doResolveBeanClass(mbd, typesToMatch);
					}
				}, getAccessControlContext());
			}
			else {
				return doResolveBeanClass(mbd, typesToMatch);
			}
		}
		catch (PrivilegedActionException pae) {
			ClassNotFoundException ex = (ClassNotFoundException) pae.getException();
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (ClassNotFoundException ex) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		catch (LinkageError err) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), err);
		}
	}

    private Class<?> doResolveBeanClass(RootBeanDefinition mbd, Class<?>... typesToMatch)
            throws ClassNotFoundException {

        ClassLoader beanClassLoader = getBeanClassLoader();
        ClassLoader classLoaderToUse = beanClassLoader;
        if (!ObjectUtils.isEmpty(typesToMatch)) {
            // When just doing type checks (i.e. not creating an actual instance yet),
            // use the specified temporary class loader (e.g. in a weaving scenario).
            ClassLoader tempClassLoader = getTempClassLoader();
            if (tempClassLoader != null) {
                classLoaderToUse = tempClassLoader;
                if (tempClassLoader instanceof DecoratingClassLoader) {
                    DecoratingClassLoader dcl = (DecoratingClassLoader) tempClassLoader;
                    for (Class<?> typeToMatch : typesToMatch) {
                        dcl.excludeClass(typeToMatch.getName());
                    }
                }
            }
        }
        String className = mbd.getBeanClassName();
        if (className != null) {
            Object evaluated = evaluateBeanDefinitionString(className, mbd);
            if (!className.equals(evaluated)) {
                // A dynamically resolved expression, supported as of 4.2...
                if (evaluated instanceof Class) {
                    return (Class<?>) evaluated;
                }
                else if (evaluated instanceof String) {
                    return ClassUtils.forName((String) evaluated, classLoaderToUse);
                }
                else {
                    throw new IllegalStateException("Invalid class name expression result: " + evaluated);
                }
            }
            // When resolving against a temporary class loader, exit early in order
            // to avoid storing the resolved Class in the bean definition.
            if (classLoaderToUse != beanClassLoader) {
                return ClassUtils.forName(className, classLoaderToUse);
            }
        }
        return mbd.resolveBeanClass(beanClassLoader);
    }

    /**
     * Evaluate the given String as contained in a bean definition,
     * potentially resolving it as an expression.
     * @param value the value to check
     * @param beanDefinition the bean definition that the value comes from
     * @return the resolved value
     * @see #setBeanExpressionResolver
     */
    protected Object evaluateBeanDefinitionString(String value, BeanDefinition beanDefinition) {
        if (this.beanExpressionResolver == null) {
            return value;
        }
        Scope scope = (beanDefinition != null ? getRegisteredScope(beanDefinition.getScope()) : null);
        return this.beanExpressionResolver.evaluate(value, new BeanExpressionContext(this, scope));
    }


    /**
     * Predict the eventual bean type (of the processed bean instance) for the
     * specified bean. Called by {@link #getType} and {@link #isTypeMatch}.
     * Does not need to handle FactoryBeans specifically, since it is only
     * supposed to operate on the raw bean type.
     * <p>This implementation is simplistic in that it is not able to
     * handle factory methods and InstantiationAwareBeanPostProcessors.
     * It only predicts the bean type correctly for a standard bean.
     * To be overridden in subclasses, applying more sophisticated type detection.
     * @param beanName the name of the bean
     * @param mbd the merged bean definition to determine the type for
     * @param typesToMatch the types to match in case of internal type matching purposes
     * (also signals that the returned {@code Class} will never be exposed to application code)
     * @return the type of the bean, or {@code null} if not predictable
     */
    protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
        Class<?> targetType = mbd.getTargetType();
        if (targetType != null) {
            return targetType;
        }
        if (mbd.getFactoryMethodName() != null) {
            return null;
        }
        return resolveBeanClass(mbd, beanName, typesToMatch);
    }

    /**
     * Check whether the given bean is defined as a {@link FactoryBean}.
     * @param beanName the name of the bean
     * @param mbd the corresponding bean definition
     */
    protected boolean isFactoryBean(String beanName, RootBeanDefinition mbd) {
        Class<?> beanType = predictBeanType(beanName, mbd, FactoryBean.class);
        return (beanType != null && FactoryBean.class.isAssignableFrom(beanType));
    }

    /**
     * Determine the bean type for the given FactoryBean definition, as far as possible.
     * Only called if there is no singleton instance registered for the target bean already.
     * <p>The default implementation creates the FactoryBean via {@code getBean}
     * to call its {@code getObjectType} method. Subclasses are encouraged to optimize
     * this, typically by just instantiating the FactoryBean but not populating it yet,
     * trying whether its {@code getObjectType} method already returns a type.
     * If no type found, a full FactoryBean creation as performed by this implementation
     * should be used as fallback.
     * @param beanName the name of the bean
     * @param mbd the merged bean definition for the bean
     * @return the type for the bean if determinable, or {@code null} else
     * @see org.springframework.beans.factory.FactoryBean#getObjectType()
     * @see #getBean(String)
     */
    protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
        if (!mbd.isSingleton()) {
            return null;
        }
        try {
            FactoryBean<?> factoryBean = doGetBean(FACTORY_BEAN_PREFIX + beanName, FactoryBean.class, null, true);
            return getTypeForFactoryBean(factoryBean);
        }
        catch (BeanCreationException ex) {
            if (ex.contains(BeanCurrentlyInCreationException.class)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Bean currently in creation on FactoryBean type check: " + ex);
                }
            }
            else if (mbd.isLazyInit()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Bean creation exception on lazy FactoryBean type check: " + ex);
                }
            }
            else {
                if (logger.isWarnEnabled()) {
                    logger.warn("Bean creation exception on non-lazy FactoryBean type check: " + ex);
                }
            }
            onSuppressedException(ex);
            return null;
        }
    }

    /**
     * Mark the specified bean as already created (or about to be created).
     * <p>This allows the bean factory to optimize its caching for repeated
     * creation of the specified bean.
     * @param beanName the name of the bean
     */
    protected void markBeanAsCreated(String beanName) {
        if (!this.alreadyCreated.contains(beanName)) {
            synchronized (this.mergedBeanDefinitions) {
                if (!this.alreadyCreated.contains(beanName)) {
                    // Let the bean definition get re-merged now that we're actually creating
                    // the bean... just in case some of its metadata changed in the meantime.
                    clearMergedBeanDefinition(beanName);
                    this.alreadyCreated.add(beanName);
                }
            }
        }
    }

    /**
     * Perform appropriate cleanup of cached metadata after bean creation failed.
     * @param beanName the name of the bean
     */
    protected void cleanupAfterBeanCreationFailure(String beanName) {
        synchronized (this.mergedBeanDefinitions) {
            this.alreadyCreated.remove(beanName);
        }
    }

    /**
     * Determine whether the specified bean is eligible for having
     * its bean definition metadata cached.
     * @param beanName the name of the bean
     * @return {@code true} if the bean's metadata may be cached
     * at this point already
     */
    protected boolean isBeanEligibleForMetadataCaching(String beanName) {
        return this.alreadyCreated.contains(beanName);
    }

    /**
     * 移除仅为类型检测创建的bean
     *
     * Remove the singleton instance (if any) for the given bean name,
     * but only if it hasn't been used for other purposes than type checking.
     * @param beanName the name of the bean
     * @return {@code true} if actually removed, {@code false} otherwise
     */
    protected boolean removeSingletonIfCreatedForTypeCheckOnly(String beanName) {
        if (!this.alreadyCreated.contains(beanName)) {
            removeSingleton(beanName);
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * Check whether this factory's bean creation phase already started,
     * i.e. whether any bean has been marked as created in the meantime.
     * @since 4.2.2
     * @see #markBeanAsCreated
     */
    protected boolean hasBeanCreationStarted() {
        return !this.alreadyCreated.isEmpty();
    }

    /**
     * <pre>
     *     <em>根据bean名称返回最终的实例对象: </em>
     *     <p>1.name标明是FactoryBean, bean实例必须是FactoryBean类型, 否则抛出异常</p>
     *     <p>2.name标明是普通bean且bean实例非FactoryBean类型</p>
     *     <p>3.继[2], 或者name标明是FactoryBean, bean实例是FactoryBean类型</p>
     *     <p>4.name标明是普通bean且bean实例是FactoryBean类型, 由bean实例创建新的bean实例</p>
     * </pre>
     *
     * bean实例本身或由FactoryBean(bean实例是FactoryBean类型)创建的对象
     *
     * @param beanInstance 共享bean实例(单例)
     * @param name bean名字(可能存在FactoryBean前缀)
     * @param beanName 标准bean名字
     * @param mbd 最终定义bean
     * @return the object to expose for the bean
     */
    protected Object getObjectForBeanInstance(
            Object beanInstance, String name, String beanName, RootBeanDefinition mbd) {

        // name标明是FactoryBean, bean实例必须是FactoryBean类型, 否则抛出异常
        // Don't let calling code try to dereference the factory if the bean isn't a factory.
        if (BeanFactoryUtils.isFactoryDereference(name) && !(beanInstance instanceof FactoryBean)) {
            throw new BeanIsNotAFactoryException(transformedBeanName(name), beanInstance.getClass());
        }

        // name标明是普通bean且bean实例非FactoryBean类型
        // 或者name标明是FactoryBean, bean实例是FactoryBean类型
        // Now we have the bean instance, which may be a normal bean or a FactoryBean.
        // If it's a FactoryBean, we use it to create a bean instance, unless the
        // caller actually wants a reference to the factory.
        if (!(beanInstance instanceof FactoryBean) || BeanFactoryUtils.isFactoryDereference(name)) {
            return beanInstance;
        }

        // name标明是普通bean且bean实例是FactoryBean类型, 由bean实例创建新的bean实例
        Object object = null;
        if (mbd == null) {
            object = getCachedObjectForFactoryBean(beanName);
        }
        if (object == null) {
            // Return bean instance from factory.
            FactoryBean<?> factory = (FactoryBean<?>) beanInstance;
            // Caches object obtained from FactoryBean if it is a singleton.
            if (mbd == null && containsBeanDefinition(beanName)) {
                mbd = getMergedLocalBeanDefinition(beanName);
            }
            boolean synthetic = (mbd != null && mbd.isSynthetic());
            object = getObjectFromFactoryBean(factory, beanName, !synthetic);
        }
        return object;
    }

    /**
     * Determine whether the given bean name is already in use within this factory,
     * i.e. whether there is a local bean or alias registered under this name or
     * an inner bean created with this name.
     * @param beanName the name to check
     */
    public boolean isBeanNameInUse(String beanName) {
        return isAlias(beanName) || containsLocalBean(beanName) || hasDependentBean(beanName);
    }

    /**
     * Determine whether the given bean requires destruction on shutdown.
     * <p>The default implementation checks the DisposableBean interface as well as
     * a specified destroy method and registered DestructionAwareBeanPostProcessors.
     * @param bean the bean instance to check
     * @param mbd the corresponding bean definition
     * @see org.springframework.beans.factory.DisposableBean
     * @see AbstractBeanDefinition#getDestroyMethodName()
     * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
     */
    protected boolean requiresDestruction(Object bean, RootBeanDefinition mbd) {
        return (bean != null &&
                (DisposableBeanAdapter.hasDestroyMethod(bean, mbd) || (hasDestructionAwareBeanPostProcessors() &&
                        DisposableBeanAdapter.hasApplicableProcessors(bean, getBeanPostProcessors()))));
    }

    /**
     * Add the given bean to the list of disposable beans in this factory,
     * registering its DisposableBean interface and/or the given destroy method
     * to be called on factory shutdown (if applicable). Only applies to singletons.
     * @param beanName the name of the bean
     * @param bean the bean instance
     * @param mbd the bean definition for the bean
     * @see RootBeanDefinition#isSingleton
     * @see RootBeanDefinition#getDependsOn
     * @see #registerDisposableBean
     * @see #registerDependentBean
     */
    protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
        AccessControlContext acc = (System.getSecurityManager() != null ? getAccessControlContext() : null);
        if (!mbd.isPrototype() && requiresDestruction(bean, mbd)) {
            if (mbd.isSingleton()) {
                // Register a DisposableBean implementation that performs all destruction
                // work for the given bean: DestructionAwareBeanPostProcessors,
                // DisposableBean interface, custom destroy method.
                registerDisposableBean(beanName,
                        new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), acc));
            }
            else {
                // A bean with a custom scope...
                Scope scope = this.scopes.get(mbd.getScope());
                if (scope == null) {
                    throw new IllegalStateException("No Scope registered for scope name '" + mbd.getScope() + "'");
                }
                scope.registerDestructionCallback(beanName,
                        new DisposableBeanAdapter(bean, beanName, mbd, getBeanPostProcessors(), acc));
            }
        }
    }


    //---------------------------------------------------------------------
    // Abstract methods to be implemented by subclasses
    //---------------------------------------------------------------------

    /**
     * Check if this bean factory contains a bean definition with the given name.
     * Does not consider any hierarchy this factory may participate in.
     * Invoked by {@code containsBean} when no cached singleton instance is found.
     * <p>Depending on the nature of the concrete bean factory implementation,
     * this operation might be expensive (for example, because of directory lookups
     * in external registries). However, for listable bean factories, this usually
     * just amounts to a local hash lookup: The operation is therefore part of the
     * public interface there. The same implementation can serve for both this
     * template method and the public interface method in that case.
     * @param beanName the name of the bean to look for
     * @return if this bean factory contains a bean definition with the given name
     * @see #containsBean
     * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
     */
    protected abstract boolean containsBeanDefinition(String beanName);

    /**
     * Return the bean definition for the given bean name.
     * Subclasses should normally implement caching, as this method is invoked
     * by this class every time bean definition metadata is needed.
     * <p>Depending on the nature of the concrete bean factory implementation,
     * this operation might be expensive (for example, because of directory lookups
     * in external registries). However, for listable bean factories, this usually
     * just amounts to a local hash lookup: The operation is therefore part of the
     * public interface there. The same implementation can serve for both this
     * template method and the public interface method in that case.
     * @param beanName the name of the bean to find a definition for
     * @return the BeanDefinition for this prototype name (never {@code null})
     * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException
     * if the bean definition cannot be resolved
     * @throws BeansException in case of errors
     * @see RootBeanDefinition
     * @see ChildBeanDefinition
     * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#getBeanDefinition
     */
    protected abstract BeanDefinition getBeanDefinition(String beanName) throws BeansException;

    /**
     * Create a bean instance for the given merged bean definition (and arguments).
     * The bean definition will already have been merged with the parent definition
     * in case of a child definition.
     * <p>All bean retrieval methods delegate to this method for actual bean creation.
     * @param beanName the name of the bean
     * @param mbd the merged bean definition for the bean
     * @param args explicit arguments to use for constructor or factory method invocation
     * @return a new instance of the bean
     * @throws BeanCreationException if the bean could not be created
     */
    protected abstract Object createBean(String beanName, RootBeanDefinition mbd, Object[] args)
            throws BeanCreationException;

}
