package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Forge's mod annotation. Forge 1.13+ reads {@code value}; Forge 1.8 to 1.12 reads {@code modid}
 * and ignores {@code value}, so one class can carry both.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value() default "";

    String modid() default "";

    String name() default "";

    String version() default "";

    String acceptableRemoteVersions() default "";

    boolean clientSideOnly() default false;
}
