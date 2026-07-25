package io.lemadane.jatot.html.spring;

import io.lemadane.jatot.html.Component;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.http.MediaType;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

public final class JatotFileRouter implements SmartInitializingSingleton {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Override
    public void afterSingletonsInstantiated() {
        String basePackage = null;
        
        // 1. Try to find SpringBootApplication annotation via reflection
        try {
            @SuppressWarnings("unchecked")
            Class<? extends java.lang.annotation.Annotation> sbAppClass = 
                (Class<? extends java.lang.annotation.Annotation>) 
                Class.forName("org.springframework.boot.autoconfigure.SpringBootApplication");
            Map<String, Object> apps = applicationContext.getBeansWithAnnotation(sbAppClass);
            if (!apps.isEmpty()) {
                Class<?> appClass = apps.values().iterator().next().getClass();
                if (appClass.getName().contains("$$")) {
                    appClass = appClass.getSuperclass();
                }
                basePackage = appClass.getPackageName() + ".routes";
            }
        } catch (Exception ignored) {
        }

        // 2. Try to find ComponentScan annotation via reflection
        if (basePackage == null) {
            try {
                @SuppressWarnings("unchecked")
                Class<? extends java.lang.annotation.Annotation> csClass = 
                    (Class<? extends java.lang.annotation.Annotation>) 
                    Class.forName("org.springframework.context.annotation.ComponentScan");
                Map<String, Object> apps = applicationContext.getBeansWithAnnotation(csClass);
                if (!apps.isEmpty()) {
                    Class<?> appClass = apps.values().iterator().next().getClass();
                    if (appClass.getName().contains("$$")) {
                        appClass = appClass.getSuperclass();
                    }
                    basePackage = appClass.getPackageName() + ".routes";
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Fallback: scan user package bean definitions, excluding third-party libs
        if (basePackage == null) {
            for (String beanName : applicationContext.getBeanDefinitionNames()) {
                try {
                    Object bean = applicationContext.getBean(beanName);
                    Class<?> beanClass = bean.getClass();
                    if (beanClass.getName().contains("$$")) {
                        beanClass = beanClass.getSuperclass();
                    }
                    String pkg = beanClass.getPackageName();
                    if (!pkg.startsWith("org.springframework") && 
                        !pkg.startsWith("java") && 
                        !pkg.startsWith("io.lemadane.jatot.html") &&
                        !pkg.startsWith("jdk") &&
                        !pkg.startsWith("com.fasterxml") &&
                        !pkg.startsWith("com.google") &&
                        !pkg.startsWith("org.apache") &&
                        !pkg.startsWith("ch.qos") &&
                        !pkg.startsWith("org.slf4j")) {
                        basePackage = pkg + ".routes";
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (basePackage == null) {
            return;
        }

        try {
            scanAndRegisterRoutes(basePackage);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure Jatot File-Based Routing", e);
        }
    }

    private void scanAndRegisterRoutes(String basePackage) throws Exception {
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(Component.class));

        Set<org.springframework.beans.factory.config.BeanDefinition> candidates = provider.findCandidateComponents(basePackage);
        for (org.springframework.beans.factory.config.BeanDefinition candidate : candidates) {
            String className = candidate.getBeanClassName();
            if (className == null || !className.endsWith(".Page")) {
                continue;
            }

            Class<?> pageClass = Class.forName(className);
            if (!Component.class.isAssignableFrom(pageClass)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Class<? extends Component> componentClass = (Class<? extends Component>) pageClass;
            registerRoute(basePackage, componentClass);
        }
    }

    private void registerRoute(String basePackage, Class<? extends Component> pageClass) throws Exception {
        String className = pageClass.getName();
        String subPackage = "";
        if (className.length() > basePackage.length() + 5) {
            subPackage = className.substring(basePackage.length() + 1, className.length() - 5);
        }

        String path = "/";
        if (!subPackage.isEmpty()) {
            String[] parts = subPackage.split("\\.");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                sb.append("/");
                if (part.startsWith("_")) {
                    sb.append("{").append(part.substring(1)).append("}");
                } else {
                    sb.append(part);
                }
            }
            path = sb.toString();
        }

        JatotRouteHandler routeHandler = new JatotRouteHandler(pageClass, findLayoutChain(basePackage, pageClass));
        Method handleMethod = JatotRouteHandler.class.getMethod("handle", Map.class, Map.class);

        RequestMappingInfo mappingInfo = RequestMappingInfo
                .paths(path)
                .methods(RequestMethod.GET)
                .produces(MediaType.TEXT_HTML_VALUE)
                .build();

        handlerMapping.registerMapping(mappingInfo, routeHandler, handleMethod);
        
        System.out.println("[Jatot Router] Registered file-based route: GET " + path + " -> " + className);
    }

    private java.util.List<Class<? extends Component>> findLayoutChain(String basePackage, Class<? extends Component> pageClass) {
        java.util.List<Class<? extends Component>> chain = new java.util.ArrayList<>();
        String pkg = pageClass.getPackageName();
        while (pkg.startsWith(basePackage)) {
            try {
                Class<?> layoutClass = Class.forName(pkg + ".Layout");
                if (Component.class.isAssignableFrom(layoutClass)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Component> compClass = (Class<? extends Component>) layoutClass;
                    chain.add(0, compClass);
                }
            } catch (ClassNotFoundException ignored) {
            }
            int lastDot = pkg.lastIndexOf('.');
            if (lastDot == -1) break;
            pkg = pkg.substring(0, lastDot);
        }
        return chain;
    }
}
