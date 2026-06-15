package com.example.jdbcexport.transform;

import com.example.jdbcexport.error.ExitCodes;
import com.example.jdbcexport.error.ExportException;
import com.example.jdbcexport.transform.builtin.AddStaticTransform;
import com.example.jdbcexport.transform.builtin.DefaultTransform;
import com.example.jdbcexport.transform.builtin.DropTransform;
import com.example.jdbcexport.transform.builtin.ExpressionTransform;
import com.example.jdbcexport.transform.builtin.KeepTransform;
import com.example.jdbcexport.transform.builtin.MapTransform;
import com.example.jdbcexport.transform.builtin.MaskTransform;
import com.example.jdbcexport.transform.builtin.RenameTransform;
import com.example.jdbcexport.transform.builtin.TemplateTransform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeSet;

/**
 * Resolves a {@link TransformSpec} to a {@link Transform}. Built-in providers are registered first;
 * external providers contributed via {@link ServiceLoader} (the {@link TransformProvider} SPI) are
 * merged in. A type collision between built-in and external providers fails fast rather than
 * silently shadowing behaviour.
 */
public final class TransformRegistry {

    private static final List<TransformProvider> BUILT_INS = List.of(
        RenameTransform.PROVIDER,
        DropTransform.PROVIDER,
        KeepTransform.PROVIDER,
        AddStaticTransform.PROVIDER,
        DefaultTransform.PROVIDER,
        MapTransform.PROVIDER,
        MaskTransform.PROVIDER,
        TemplateTransform.PROVIDER,
        ExpressionTransform.PROVIDER
    );

    private final Map<String, TransformProvider> providers = new LinkedHashMap<>();

    public TransformRegistry() {
        this(ServiceLoader.load(TransformProvider.class));
    }

    TransformRegistry(Iterable<TransformProvider> external) {
        for (TransformProvider provider : BUILT_INS) {
            providers.put(provider.type(), provider);
        }
        for (TransformProvider provider : external) {
            if (providers.putIfAbsent(provider.type(), provider) != null) {
                throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                    "Transform provider conflict: type \"" + provider.type() + "\" is already registered.");
            }
        }
    }

    public OutboundTransformer create(TransformSpec spec) {
        TransformProvider provider = providers.get(spec.type());
        if (provider == null) {
            throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                "Unknown transform type \"" + spec.type() + "\". Known types: " + new TreeSet<>(providers.keySet()) + ".");
        }
        return provider.create(spec);
    }

    public TransformPipeline build(List<TransformSpec> specs) {
        return build(new TransformConfig(specs, ErrorStrategy.FAIL, null));
    }

    public TransformPipeline build(TransformConfig config) {
        List<TransformPipeline.Step> steps = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (TransformSpec spec : config.specs()) {
            String explicit = spec.explicitName();
            if (explicit != null && !names.add(explicit)) {
                throw new ExportException(ExitCodes.TRANSFORM_ERROR,
                    "Duplicate transform name \"" + explicit + "\" in pipeline; names must be unique.");
            }
            steps.add(new TransformPipeline.Step(spec.name(), create(spec)));
        }
        return new TransformPipeline(steps, config.errorStrategy());
    }
}
