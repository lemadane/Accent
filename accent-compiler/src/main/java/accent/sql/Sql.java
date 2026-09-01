package accent.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public class Sql {
    private static DataSource dataSource;

    public static void setDataSource(DataSource ds) {
        dataSource = ds;
    }

    public static Object execute(String sql, List<Object> params, Class<?> resultClass) {
        if (dataSource == null) {
            throw new IllegalStateException("Accent Database DataSource has not been initialized. Call Db.setDataSource(...) first.");
        }

        String trimmed = sql.trim().toUpperCase();
        boolean isSelect = trimmed.startsWith("SELECT") || trimmed.startsWith("WITH") || trimmed.startsWith("SHOW") || trimmed.startsWith("DESCRIBE");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            if (isSelect) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<Object> list = new ArrayList<>();
                    if (resultClass == null || resultClass == Void.class) {
                        return list;
                    }
                    while (rs.next()) {
                        list.add(mapRow(rs, resultClass));
                    }
                    return list;
                }
            } else {
                return ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException("Database execution failed: " + e.getMessage(), e);
        }
    }

    private static Object mapRow(ResultSet rs, Class<?> clazz) throws Exception {
        if (clazz == Map.class) {
            Map<String, Object> map = new HashMap<>();
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            for (int i = 1; i <= cols; i++) {
                map.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            return map;
        }

        if (clazz.isRecord()) {
            Map<String, Object> values = new HashMap<>();
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            for (int c = 1; c <= cols; c++) {
                values.put(meta.getColumnLabel(c), rs.getObject(c));
            }
            int[] indexTracker = {1};
            return instantiateRecord(clazz, values, indexTracker, rs);
        }

        Object val = rs.getObject(1);
        return convertValue(val, clazz);
    }

    private static Object instantiateRecord(Class<?> clazz, Map<String, Object> values, int[] indexTracker, ResultSet rs) throws Exception {
        java.lang.reflect.Constructor<?> constructor = clazz.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        java.lang.reflect.Parameter[] parameters = constructor.getParameters();
        Object[] args = new Object[parameters.length];
        
        for (int i = 0; i < parameters.length; i++) {
            java.lang.reflect.Parameter param = parameters[i];
            String name = param.getName();
            Class<?> type = param.getType();
            
            if (type.isRecord()) {
                args[i] = instantiateRecord(type, values, indexTracker, rs);
            } else {
                Object val = null;
                if (!name.startsWith("arg")) {
                    val = getIgnoreCase(values, name);
                }
                if (val == null) {
                    int idx = indexTracker[0];
                    ResultSetMetaData meta = rs.getMetaData();
                    if (idx <= meta.getColumnCount()) {
                        val = rs.getObject(idx);
                        indexTracker[0] = idx + 1;
                    }
                }
                
                if (val == null) {
                    args[i] = getDefaultValue(type);
                } else {
                    args[i] = convertValue(val, type);
                }
            }
        }
        return constructor.newInstance(args);
    }

    private static Object getIgnoreCase(Map<String, Object> values, String name) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Object convertValue(Object value, Class<?> type) {
        if (value == null) return getDefaultValue(type);
        if (type.isInstance(value)) return value;

        String str = value.toString();
        if (type == String.class) {
            return str;
        } else if (type == int.class || type == Integer.class) {
            return Integer.parseInt(str);
        } else if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Number) {
                return ((Number) value).intValue() != 0;
            }
            return Boolean.parseBoolean(str);
        } else if (type == long.class || type == Long.class) {
            return Long.parseLong(str);
        } else if (type == double.class || type == Double.class) {
            return Double.parseDouble(str);
        }
        return value;
    }

    private static Object getDefaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == String.class) return "";
        return null;
    }
}
