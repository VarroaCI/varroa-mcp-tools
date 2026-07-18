package io.jenkins.plugins.varroamcptools.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonResultBuilderTest {

    @Test
    void shouldProduceStableKeyOrder() {
        JsonResultBuilder builder1 =
                JsonResultBuilder.create().add("alpha", 1).add("beta", "two").add("gamma", true);

        JsonResultBuilder builder2 =
                JsonResultBuilder.create().add("alpha", 1).add("beta", "two").add("gamma", true);

        LinkedHashMap<String, Object> map1 = builder1.build();
        LinkedHashMap<String, Object> map2 = builder2.build();

        assertThat(map1.keySet()).containsExactly("alpha", "beta", "gamma");
        assertThat(map1.keySet()).isEqualTo(map2.keySet());

        List<String> keys1 = List.copyOf(map1.keySet());
        List<String> keys2 = List.copyOf(map2.keySet());
        assertThat(keys1).isEqualTo(keys2);
    }

    @Test
    void shouldProduceInsertionOrderWhenFieldsDiffer() {
        JsonResultBuilder builder =
                JsonResultBuilder.create().add("zulu", 1).add("alpha", 2).add("mike", 3);

        LinkedHashMap<String, Object> map = builder.build();

        assertThat(map.keySet()).containsExactly("zulu", "alpha", "mike");
    }

    @Test
    void shouldSupportNullValues() {
        JsonResultBuilder builder =
                JsonResultBuilder.create().add("present", "value").add("missing", null);

        LinkedHashMap<String, Object> map = builder.build();

        assertThat(map).containsKey("missing");
        assertThat(map.get("missing")).isNull();
        assertThat(map.get("present")).isEqualTo("value");
    }

    @Test
    void shouldAllowChaining() {
        LinkedHashMap<String, Object> map =
                JsonResultBuilder.create().add("a", 1).add("b", 2).add("c", 3).build();

        assertThat(map).hasSize(3);
    }

    @Test
    void shouldNotAcceptCoreModelTypes() {
        List<String> forbidden = List.of("hudson.model.Run", "hudson.model.Job", "hudson.model.Computer");

        for (Method method : JsonResultBuilder.class.getDeclaredMethods()) {
            for (Class<?> paramType : method.getParameterTypes()) {
                String name = paramType.getName();
                assertThat(forbidden)
                        .as("Method %s accepts forbidden type %s", method.getName(), name)
                        .doesNotContain(name);
            }
        }
    }

    @Test
    void shouldBeFluentWithAddReturningThis() {
        JsonResultBuilder builder = JsonResultBuilder.create();
        assertThat(builder.add("key", "value")).isSameAs(builder);
    }
}
