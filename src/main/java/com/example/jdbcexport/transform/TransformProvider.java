package com.example.jdbcexport.transform;

/**
 * Service-provider interface for building a {@link Transform} from declarative configuration.
 *
 * <p>Built-in transforms register through {@link TransformRegistry}. Third parties supply advanced
 * transforms by implementing this interface and declaring it in
 * {@code META-INF/services/com.example.jdbcexport.transform.TransformProvider}; the registry merges
 * them via {@link java.util.ServiceLoader}. This is a compile-time/SPI extension point only — no
 * untrusted code is ever loaded or evaluated at run time.
 */
public interface TransformProvider {

    /** The transform type this provider builds (e.g. {@code "rename"}). */
    String type();

    /** Validate {@code spec} and build the transformer, throwing {@link com.example.jdbcexport.error.ExportException} on bad config. */
    OutboundTransformer create(TransformSpec spec);
}
