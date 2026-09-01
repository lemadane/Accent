package io.lemadane.accent.html.spring;

import io.lemadane.accent.html.Component;
import io.lemadane.accent.html.Html;
import io.lemadane.accent.html.HtmlChildren;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import java.util.Map;

public final class AccentRouteHandler {

    private final Class<? extends Component> pageClass;
    private final List<Class<? extends Component>> layoutChain;
    private final Class<?> loaderClass;
    private final String cachedHtml;

    public AccentRouteHandler(Class<? extends Component> pageClass, List<Class<? extends Component>> layoutChain) {
        this.pageClass = pageClass;
        this.layoutChain = layoutChain;
        
        Class<?> loader = null;
        try {
            loader = Class.forName(pageClass.getPackageName() + ".Loader");
        } catch (ClassNotFoundException ignored) {
        }
        this.loaderClass = loader;

        String html = null;
        if (pageClass.isAnnotationPresent(Prerender.class)) {
            if (pageClass.getConstructors()[0].getParameterCount() == 0) {
                try {
                    Component currentComponent = (Component) pageClass.getConstructors()[0].newInstance();
                    for (int i = layoutChain.size() - 1; i >= 0; i--) {
                        Class<? extends Component> layoutClass = layoutChain.get(i);
                        final Component innerComponent = currentComponent;
                        HtmlChildren children = writer -> innerComponent.writeTo(writer);
                        currentComponent = (Component) layoutClass.getConstructors()[0].newInstance(children);
                    }
                    html = currentComponent.renderToString();
                    System.out.println("[Accent Router] Prerendered page: " + pageClass.getName() + " (HTML length: " + html.length() + ")");
                } catch (Exception e) {
                    System.err.println("[Accent Router] Failed to prerender page " + pageClass.getName() + ": " + e.getMessage());
                }
            } else {
                System.err.println("[Accent Router] Skipping @Prerender for " + pageClass.getName() + " because its constructor requires arguments.");
            }
        }
        this.cachedHtml = html;
    }

    public Component handle(
            @PathVariable Map<String, String> pathVariables,
            @RequestParam Map<String, String> queryParams
    ) throws Exception {
        if (cachedHtml != null) {
            return () -> writer -> writer.literal(cachedHtml);
        }

        Object loadData = null;
        if (loaderClass != null) {
            loadData = executeLoader(loaderClass, pathVariables, queryParams);
        }

        Component currentComponent = instantiateComponent(pageClass, pathVariables, queryParams, null, loadData);

        for (int i = layoutChain.size() - 1; i >= 0; i--) {
            Class<? extends Component> layoutClass = layoutChain.get(i);
            final Component innerComponent = currentComponent;
            HtmlChildren children = writer -> innerComponent.writeTo(writer);
            currentComponent = instantiateComponent(layoutClass, pathVariables, queryParams, children, null);
        }

        return currentComponent;
    }

    private Object executeLoader(Class<?> loaderClass, Map<String, String> pathVariables, Map<String, String> queryParams) throws Exception {
        java.lang.reflect.Constructor<?> constructor = loaderClass.getConstructors()[0];
        java.lang.reflect.Parameter[] parameters = constructor.getParameters();
        Object[] args = new Object[parameters.length];
        
        for (int i = 0; i < parameters.length; i++) {
            java.lang.reflect.Parameter param = parameters[i];
            String name = param.getName();
            Class<?> type = param.getType();
            args[i] = resolveParameter(name, type, pathVariables, queryParams);
        }
        Object loaderInstance = constructor.newInstance(args);

        java.lang.reflect.Method loadMethod = null;
        for (java.lang.reflect.Method m : loaderClass.getMethods()) {
            if (m.getName().equals("load")) {
                loadMethod = m;
                break;
            }
        }
        if (loadMethod == null) {
            throw new IllegalStateException("Loader class " + loaderClass.getName() + " must define a 'load' method.");
        }

        java.lang.reflect.Parameter[] loadParams = loadMethod.getParameters();
        Object[] loadArgs = new Object[loadParams.length];
        for (int i = 0; i < loadParams.length; i++) {
            java.lang.reflect.Parameter param = loadParams[i];
            String name = param.getName();
            Class<?> type = param.getType();
            loadArgs[i] = resolveParameter(name, type, pathVariables, queryParams);
        }
        return loadMethod.invoke(loaderInstance, loadArgs);
    }

    private Component instantiateComponent(
            Class<? extends Component> clazz,
            Map<String, String> pathVariables,
            Map<String, String> queryParams,
            HtmlChildren children,
            Object loadData
    ) throws Exception {
        java.lang.reflect.Constructor<?>[] constructors = clazz.getConstructors();
        if (constructors.length == 0) {
            throw new IllegalStateException("No public constructors found for " + clazz.getName());
        }
        
        java.lang.reflect.Constructor<?> constructor = constructors[0];
        java.lang.reflect.Parameter[] parameters = constructor.getParameters();
        Object[] args = new Object[parameters.length];
        
        for (int i = 0; i < parameters.length; i++) {
            java.lang.reflect.Parameter param = parameters[i];
            String name = param.getName();
            Class<?> type = param.getType();
            
            if ((type == HtmlChildren.class || type == Html.class) && children != null) {
                args[i] = children;
            } else if (loadData != null && type.isAssignableFrom(loadData.getClass())) {
                args[i] = loadData;
            } else if (loadData != null && loadData.getClass().isRecord() && getRecordComponentValue(loadData, name, type) != null) {
                args[i] = getRecordComponentValue(loadData, name, type);
            } else {
                args[i] = resolveParameter(name, type, pathVariables, queryParams);
            }
        }
        
        return (Component) constructor.newInstance(args);
    }

    private Object getRecordComponentValue(Object record, String name, Class<?> targetType) {
        try {
            java.lang.reflect.RecordComponent[] components = record.getClass().getRecordComponents();
            for (java.lang.reflect.RecordComponent rc : components) {
                if (rc.getName().equals(name) && targetType.isAssignableFrom(rc.getType())) {
                    return rc.getAccessor().invoke(record);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Object resolveParameter(String name, Class<?> type, Map<String, String> pathVariables, Map<String, String> queryParams) throws Exception {
        String value = pathVariables.get(name);
        if (value == null) {
            value = queryParams.get(name);
        }
        
        if (value != null) {
            return convertValue(value, type);
        } else {
            if (type.isRecord()) {
                return instantiateRecord(type, pathVariables, queryParams);
            } else {
                return getDefaultValue(type);
            }
        }
    }
    
    private Object instantiateRecord(Class<?> recordClass, Map<String, String> pathVariables, Map<String, String> queryParams) throws Exception {
        java.lang.reflect.Constructor<?> constructor = recordClass.getConstructors()[0];
        java.lang.reflect.Parameter[] parameters = constructor.getParameters();
        Object[] args = new Object[parameters.length];
        
        for (int i = 0; i < parameters.length; i++) {
            java.lang.reflect.Parameter param = parameters[i];
            String name = param.getName();
            Class<?> type = param.getType();
            
            String value = pathVariables.get(name);
            if (value == null) {
                value = queryParams.get(name);
            }
            
            if (value != null) {
                args[i] = convertValue(value, type);
            } else {
                if (type == String.class && name.equals("email") && pathVariables.containsKey("name")) {
                    args[i] = pathVariables.get("name").toLowerCase() + "@example.com";
                } else {
                    args[i] = getDefaultValue(type);
                }
            }
        }
        return constructor.newInstance(args);
    }

    private Object convertValue(String value, Class<?> type) {
        if (type == String.class) {
            return value;
        } else if (type == int.class || type == Integer.class) {
            return Integer.parseInt(value);
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(value);
        } else if (type == long.class || type == Long.class) {
            return Long.parseLong(value);
        } else if (type == double.class || type == Double.class) {
            return Double.parseDouble(value);
        }
        throw new IllegalArgumentException("Unsupported parameter type: " + type.getName());
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == String.class) return "";
        return null;
    }
}
