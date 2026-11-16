package accent.runtime;

import accent.json.JsonOptions;
import accent.json.internal.JsonStringifier;
import accent.xml.internal.XmlWriter;

/** Runtime targets for compiler-generated native serialization methods. */
public final class DataSerialization {
    private DataSerialization() {}

    public static String toJson(Object value) {
        return new JsonStringifier(JsonOptions.DEFAULT).stringify(value);
    }

    public static String toXml(Object value) {
        return XmlWriter.write(value);
    }
}
