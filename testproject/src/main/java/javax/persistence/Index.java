package javax.persistence;

import java.lang.annotation.*;

@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface Index {
    String name() default "";

    String columnList() default "";

    boolean unique() default false;
}
